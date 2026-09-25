# Architecture

Online Order is one Spring Boot application with one PostgreSQL database. Inside, the code is
split into modules with one-way dependencies:

```
kitchen (web) ──▶ payment ──▶ ordering ──▶ inventory
                      │           │
                      └─────┬─────┘
                            ▼
                         platform   (Tx, outbox + dispatcher, LISTEN/NOTIFY hub, API errors)
```

`ordering` never imports `payment`. The kitchen board needs both (orders and the ledger), so its
controller lives in its own `kitchen` package on top. When an order is cancelled, `ordering` publishes an
`OrderCancelled` event synchronously, inside the cancelling transaction, and `payment` refunds
in that same transaction. The catalog, cart and customer code from the original project stays
in `controller/`, `service/` and `repository/`.

The rest of this document is a set of decision records: what was decided, why, and what it
costs.

---

## ADR 1 — A modular monolith, not microservices

**Context.** Checkout touches the cart, prices, stock, the order, its audit trail and the
customer notification. Payment touches the payment, the order and the ledger. These must change
together or not at all.

**Decision.** Keep one deployable and one database. Enforce module boundaries in code (packages,
constructor dependencies, no cycles) instead of over the network.

**Why.** Across services, every one of those use cases would become a saga with compensations
and eventual consistency, for a domain with no independent scaling or team boundaries to justify
it. In one database, "all or nothing" is simply a transaction. [SocialAI](https://github.com/Rolinmuuu/Social-AI-Backend)
is the counterpoint: there the read fan-out and independent workers do justify services and
Kafka.

**Cost / exit.** A single database is the scaling ceiling (see SCALING.md for where it bends).
If a module has to leave, the seams are already there: `payment` already talks to `ordering` only
through `Orders.transition` and an event, and the outbox can publish to a broker instead of
in-process handlers.

## ADR 2 — READ COMMITTED, with the correctness in the statements

**Decision.** Every unit of work runs through `Tx` at READ COMMITTED. Races are handled by what
the statements themselves guarantee:

| Race | Statement | Why it is safe |
|---|---|---|
| two checkouts take the last unit | `UPDATE inventory SET available = available - q WHERE menu_item_id = ? AND available >= q` | a transaction that waits on the row lock re-evaluates the WHERE clause against the committed row (PostgreSQL's EvalPlanQual), so the second one sees 0 and updates nothing |
| kitchen accepts while the customer cancels | `UPDATE orders SET status = ? WHERE id = ? AND status = ?` | exactly one UPDATE matches; the other gets 409 `STATUS_CHANGED` |
| same Idempotency-Key twice at once | `INSERT … ON CONFLICT DO NOTHING RETURNING` on the key's primary key | the second insert waits for the first transaction, then sees the conflict and replays |
| same webhook twice at once | same pattern on `payment_events(provider_event_id)` | only one transaction applies it |
| two sweepers / dispatchers | `SELECT … FOR UPDATE SKIP LOCKED` | each claims different rows and none waits |

**Why not SERIALIZABLE.** It would also be correct, but under contention on a hot inventory
row it aborts transactions with 40001 and forces retries of the whole checkout. The statements
above never need to be retried. `Tx` still retries deadlocks (40P01) and serialization failures
up to three times, as a safety net.

**Proof.** `InventoryConcurrencyIT` includes the naive version (read the stock, decide in Java,
write it back) and shows it oversells 5 units to 20 buyers. It also includes two transactions
locking two rows in opposite order, which PostgreSQL aborts as a deadlock. Sorted locking removes
that.

## ADR 3 — Checkout is one transaction, ordered for contention

```
 1. claim Idempotency-Key            (a concurrent duplicate waits here, then replays)
 2. lock the cart's item rows, then the cart row   (the order add-to-cart uses, see below)
 3. price the cart in cents          (price, name copied into order_lines)
 4. insert order, lines, event PLACED, outbox notification, pg_notify
 5. empty the cart
 6. reserve limited stock            (ascending item id; LAST)
 7. link key → order, COMMIT
```

Stock is reserved **last**. The inventory row of a popular dish is the one lock every checkout
competes for, and a row lock is held until COMMIT, so taking it last shortens the time each
transaction holds it. Measured over interleaved runs: about 1.8× the throughput at 8 concurrent
buyers of one dish and 1.4× at 32 (SCALING.md). The cost is that a sold-out checkout does its
inserts before rolling back. That work is cheap and never visible.

**Lock order with add-to-cart.** `CartService.addMenuItemToCart` updates an item row, then saves
the cart row with its `@Version`. Checkout takes the item rows (`FOR UPDATE`) **before** the cart
row. With the opposite order the two deadlock; `CheckoutIT` replays add-to-cart statement by
statement against checkout and fails if they do. An add that loses the race fails loudly with an
optimistic-lock or "row gone" error, and `CartController` runs it again, which puts the item in
the now-empty cart. Holding the cart row until COMMIT also stops an add from committing between
checkout reading the cart and emptying it.

The customer sends the total they were shown. If a price changed in between, checkout fails with
409 `PRICE_CHANGED`, which includes the new total. The client asks the customer to confirm it
and places the order again, rather than charging an amount the customer never saw.

## ADR 4 — Order state machine with a single choke point

`OrderStatus` defines the allowed moves (PLACED → PAID → ACCEPTED → READY → COMPLETED, and
cancellation from PLACED, PAID or ACCEPTED). `Orders.transition` is the only code that changes a
status. It always does four things in the caller's transaction:

1. the guarded UPDATE, which gives 409 if the order moved meanwhile;
2. an append-only `order_events` row (who, from, to, why);
3. an outbox message for the customer notification;
4. `pg_notify` for live screens.

Because it is one function, no transition can forget the audit row or the notification.

## ADR 5 — Payments: never call the provider inside a transaction

- **Charge.** The `payments` row is created in a transaction, and the provider is called **after
  commit**. A slow provider must not hold row locks or a pooled connection. The provider call is
  idempotent on the payment reference, so a double-clicked Pay button charges once.
- **Result.** The result arrives as a webhook, signed like Stripe's
  (`t=<ts>,v1=HMAC-SHA256(secret, t.body)`). Verification uses a constant-time comparison and a
  5-minute timestamp window, which stops replays. Applying an event starts by inserting its id
  into `payment_events`, so redeliveries are no-ops. The simulated provider delivers every event
  twice on purpose.
- **Late payment.** If the payment lands after the order expired, the capture is posted and
  refunded in the same transaction; the provider refund goes out through the outbox. The
  customer's money comes back automatically; the order is not revived.
- **Wrong amount.** If the provider captured a different amount than the order's, it is not
  accepted as payment. The movement is posted against `customer_refunds_payable` and refunded
  in full.
- **Retry.** A failed payment can be retried. The retry is a new charge with a new reference, so
  a late event for the old charge matches nothing.
- **Lock order.** The webhook locks the payment, then the order. Cancelling a *paid* order locks
  the order, then the payment, but a paid order's payment is already CAPTURED, so a webhook for
  it returns right after its own lock and never asks for the order. The sweeper only cancels
  unpaid orders, which have nothing to refund.
- **Refund.** A refund is decided inside the cancelling transaction: the payment is marked
  REFUNDED and the ledger is reversed. The provider call is queued in the outbox with
  `refund-<outbox id>` as its idempotency key, so a redelivered message refunds once.

## ADR 6 — Double-entry ledger enforced by the database

A capture of T cents posts three legs that sum to zero: `processor_receivable +T`,
`restaurant_payable:{id} −(T − fee)` and `platform_revenue −fee` (15%, rounded down). A refund
posts the negation. Two database triggers back this up:

- a **deferred constraint trigger** rejects any transaction whose legs do not sum to zero, at
  COMMIT, so legs can still be inserted one at a time;
- a **BEFORE UPDATE/DELETE trigger** makes entries append-only.

So a bug in `Ledger` cannot produce an unbalanced posting or rewrite history. It could still
post a wrong but balanced amount; the tests check the amounts. The kitchen board's "owed to this
restaurant" figure is a SUM over the ledger, not a counter that could drift. (`INIT_DB=always`,
the demo default, rebuilds the whole database at startup; a real deployment runs with `never`.)

## ADR 7 — PostgreSQL instead of a message broker

- **Outbox.** Side effects (notifications, provider refunds) are rows in `outbox`, written in
  the transaction that causes them. `OutboxDispatcher` claims a batch in one short transaction
  (`FOR UPDATE SKIP LOCKED`, then a 60 s lease), so any number of instances can dispatch. Each
  message is then delivered on its own:
  - a database-only handler (notifications) runs in the transaction that marks the row
    delivered, so it is exactly-once;
  - a handler that calls another system (refunds) runs outside any transaction, so no locks or
    pooled connection are held while waiting on the network. It is at-least-once, with
    `refund-<outbox id>` as the provider's idempotency key.

  A failing message is retried with exponential backoff and does not block the others. Delivered
  rows are pruned after 7 days, idempotency keys after 24 hours.
- **Live updates.** `pg_notify('order_updates', …)` runs in the same transaction. PostgreSQL
  delivers it only after COMMIT, to every instance that `LISTEN`s on its own dedicated connection
  (not a pooled one). Each instance pushes it to its browsers over Server-Sent Events, filtered
  per customer or per restaurant, from a small sender pool so a slow browser cannot stall the
  others. An idle listen connection is probed every 10 s. After a reconnect, every open stream
  gets a `ready` event and re-fetches, because notifications sent while the listener was down
  are gone. A missed message therefore delays an update but never leaves a screen wrong. Browsers
  whose stream failed with an HTTP error reopen it every 5 s.
- **Why.** One fewer system to run, and the broker would have given weaker guarantees here:
  publishing to Kafka cannot be part of the database transaction. That is the dual-write problem
  SocialAI needs an outbox relay for.
- **Limits.** NOTIFY payloads max out at 8 KB, so only ids and the status are sent. Each instance
  holds one extra connection for LISTEN. Commits that sent a notification are serialised on a
  global queue lock. The outbox polls every 500 ms, so there is up to about 0.5 s of delay. Past
  thousands of messages per second a broker would be the next step, fed from this same outbox.

## ADR 8 — Unpaid orders expire; stock comes back exactly once

An order holds its stock for `ORDER_PAY_WITHIN_SECONDS` (default 15 minutes). A sweeper running
every 5 s selects `status = 'PLACED' AND pay_by < now()` with `FOR UPDATE SKIP LOCKED` and
cancels those orders, which releases their stock. A partial index
`(pay_by) WHERE status = 'PLACED'` keeps the query proportional to the number of unpaid orders,
not to all orders ever placed. An order being paid at that moment is either locked by the
webhook (skipped, reconsidered next pass) or already PAID (no longer matches).

---

## Fixed along the way

| Before | Effect | Now |
|---|---|---|
| `order_items.menu_item_id UNIQUE` | only one cart in the whole system could contain a given dish | `UNIQUE (cart_id, menu_item_id)` |
| "checkout" only emptied the cart | no order existed | real orders, lines, payments |
| cart total updated read-then-write | concurrent adds lost updates | `@Version` + retry in the controller (3 simultaneous adds → quantity 3, checked over HTTP) |
| authorities query selected a non-existent column (`authorities`) | PostgreSQL returned the whole row as the "authority" | `SELECT email, authority …` |
| money as `double` | rounding drift | new tables store integer cents |
| sample data used real brand names and DoorDash CDN photos | not appropriate for a public demo | fictional restaurants, illustrations generated from code |

## Known gaps

- Kitchen staff are assigned in the `restaurant_staff` table. There is no admin screen for it.
- The simulated provider always succeeds. Payment failure, retry and wrong-amount handling are
  covered by integration tests only.
- Refund-through-Spring-events is exercised against the real application context by
  `OnlineOrderApplicationTests` (runs in CI) and was checked by hand over HTTP. The integration
  tests wire the services by hand.
- Demo mode (`APP_DEMO=true`, the default) creates `kitchen@mail.com`, which is staff of every
  restaurant, and uses the simulated processor. With `APP_DEMO=false` neither exists, and the app
  refuses to start without a real webhook secret (and, until one is written, a real
  `PaymentProvider`).
- The kitchen board lists active orders plus those finished in the last two hours, with no
  paging.
- Stock is one row per dish. At very high contention it should be split into buckets (see
  SCALING.md).
