# Online Order

A food-ordering platform built as a **modular monolith on PostgreSQL**: Spring Boot 3 (Java 21), React 18. The emphasis is on the parts of an ordering system that must stay correct when many people act at once. Limited dishes are never oversold, a retried checkout never creates a second order, a duplicated payment webhook never charges twice, and every cent moves through a double-entry ledger.

![Menu with limited daily dishes and the cart](docs/menu.png)

| Order tracking, updated live | Kitchen board, updated live |
|---|---|
| ![Order page](docs/order-live.png) | ![Kitchen board](docs/kitchen.png) |

Screenshots of the real application running locally against PostgreSQL. The sample data (3 fictional restaurants, orders placed by a seeding script) and the food illustrations, which are generated from code in `doordash-app/scripts/food-art.py`, are not real customers or brands. Payments go through a built-in **simulated** card processor.

## What it does

- **Customers** browse three kitchens, add dishes to a cart and place an order. Some dishes are cooked in limited daily batches ("Only 7 left today"). The order holds its portion for 15 minutes while the customer pays, then status updates arrive live: paid → preparing → ready → picked up.
- **Kitchen staff** see paid orders appear on a board the moment the payment clears. They accept, reject (which refunds the customer automatically), mark ready and hand over. The board also shows what the platform owes the restaurant, read from the ledger.
- **The system** cancels unpaid orders when their window closes and returns their stock. It refunds a payment that lands after the order expired, and it delivers notifications and refunds through a transactional outbox.

## Architecture in one picture

```mermaid
flowchart LR
  B[React app] -- REST + session --> API
  B -. Server-Sent Events .- HUB
  subgraph APP[Spring Boot — one deployable, four modules]
    API[controllers]
    ORD[ordering<br/>checkout, state machine,<br/>expiry sweeper]
    INV[inventory<br/>conditional decrements]
    PAY[payment<br/>webhooks, refunds, ledger]
    KIT[kitchen<br/>board: orders + ledger]
    PLT[platform<br/>Tx, outbox dispatcher,<br/>LISTEN/NOTIFY hub]
    API --> ORD & PAY & INV & KIT
    KIT --> PAY --> ORD --> INV
    ORD & PAY --> PLT
    HUB[order updates hub]
  end
  APP == one ACID transaction per use case ==> PG[(PostgreSQL<br/>orders, stock, ledger,<br/>outbox, idempotency keys)]
  PG -- NOTIFY on commit --> HUB
  SIM[Simulated card processor] -- signed webhook, delivered twice --> API
  APP -. menu cache .-> R[(Redis, optional)]
```

The same problems as [SocialAI](https://github.com/Rolinmuuu/Social-AI-Backend) are solved here with the opposite choices, on purpose:

| | SocialAI | Online Order |
|---|---|---|
| Shape | Go microservices | One Spring Boot service, split into modules with one-way dependencies |
| Consistency | eventual, event-driven | strong: each use case is one PostgreSQL transaction |
| Messaging | Kafka | PostgreSQL: outbox table + `FOR UPDATE SKIP LOCKED` workers, `LISTEN/NOTIFY` for live updates |
| Idempotency | Redis `SET NX` | a unique key claimed inside the business transaction |
| Hard part | read fan-out, hot documents | write contention: stock, payments, state races |

Why this project does not use microservices, and the other decisions, are in **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**, written as decision records. Measured throughput and the bottleneck analysis are in **[docs/SCALING.md](docs/SCALING.md)**. The AWS target design is in **[docs/CLOUD.md](docs/CLOUD.md)** (a design only, not deployed).

## Guarantees and the test behind each

All of these are integration tests against a real PostgreSQL (`OnlineOrder/src/test/java/.../it`), because they depend on the database's own behaviour: locks, constraints, deferred triggers and NOTIFY.

| Guarantee | How | Test |
|---|---|---|
| Limited stock is never oversold | `UPDATE … SET available = available - q WHERE available >= q` (re-checked after a lock wait) + `CHECK (available >= 0)` | 40 customers race for 10 units → exactly 10 orders (`InventoryConcurrencyIT`) |
| No deadlocks between orders | rows locked in ascending id order | opposite order deadlocks, sorted order does not (same test class) |
| Add-to-cart and checkout never deadlock | both take the item rows before the cart row | `CheckoutIT` replays add-to-cart against checkout; the old lock order fails it |
| A retried checkout makes one order | `Idempotency-Key` inserted in the checkout transaction; a concurrent duplicate waits on the unique index, then replays | 20 concurrent retries → 1 order (`CheckoutIT`) |
| No charge for an amount the customer did not see | client sends the displayed total; mismatch → 409 `PRICE_CHANGED` with the new total for the customer to confirm | `CheckoutIT` |
| Live updates only for committed changes | `pg_notify` inside the transaction | rolled-back checkout announces nothing (`CheckoutIT`) |
| Races between the kitchen and the customer have one winner | guarded update `… WHERE id = ? AND status = ?` | accept vs cancel, 15 rounds (`OrderLifecycleIT`) |
| Duplicate or forged webhooks do nothing | provider event id is the primary key of `payment_events`; HMAC-SHA256 signature with a 5-minute timestamp window | 5 simultaneous deliveries → 1 capture; forged, stale and tampered bodies → 401 |
| Money that should not have been taken goes back | late payment after expiry, wrong captured amount, rejected order → refund via the outbox | `OrderLifecycleIT` |
| Money always balances | double-entry ledger; a deferred constraint trigger rejects an unbalanced transaction at COMMIT; entries are append-only | `LedgerAndOutboxIT` |
| Unpaid orders release stock exactly once | sweeper claims rows with `FOR UPDATE SKIP LOCKED` | two sweepers in parallel → each order cancelled once |
| Side effects happen iff the transaction commits | transactional outbox; claim with a lease, deliver outside the claim; external calls outside any transaction | 3 parallel dispatchers, 300 messages → 300 deliveries; a failing handler is retried later |

## Run it

```bash
cd OnlineOrder
docker compose up -d                 # PostgreSQL 15 + Redis (CI and the benchmarks use 16)
./gradlew bootRun                    # http://localhost:8080 (serves the built frontend too)
```

Sign in as `foo@mail.com` (customer) or `kitchen@mail.com` (kitchen staff of all three restaurants), password `123456`. Use two browser windows to watch the kitchen and the customer update each other live. `CACHE_TYPE=simple ./gradlew bootRun` runs without Redis: PostgreSQL is the only required dependency.

Frontend development server: `cd doordash-app && npm ci && npm start` (port 3000, proxies to 8080).

## Test

```bash
cd OnlineOrder && ./gradlew test     # unit tests + integration tests; needs the PostgreSQL from docker compose
cd doordash-app && CI=true npm test
```

The integration tests connect with `TEST_DATABASE_URL` (default `jdbc:postgresql://localhost:5432/onlineorder_test`, a database of its own: create it once with `createdb -h localhost -U postgres onlineorder_test`), `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`, and rebuild the schema before each test. CI runs both suites on every push (`.github/workflows/ci.yml`).

## API

| Method | Path | Who | Notes |
|---|---|---|---|
| `POST` | `/signup`, `/login`, `/logout` | public | form login, session cookie |
| `GET` | `/me` | signed in | current user, whether they are kitchen staff |
| `GET` | `/restaurants/menu`, `/inventory` | public | menus (cached), units left of limited dishes |
| `GET` `POST` | `/cart`, `/cart/clear` | customer | add to cart retries on optimistic-lock conflicts (`/cart/checkout` is an old alias of `/cart/clear`) |
| `POST` | `/orders` | customer | checkout; header `Idempotency-Key`, body `{"expected_total_cents": …}`; 409 `OUT_OF_STOCK` / `PRICE_CHANGED` / `MIXED_RESTAURANTS` |
| `GET` | `/orders`, `/orders/{id}` | customer | with lines and the audit trail |
| `POST` | `/orders/{id}/pay`, `/orders/{id}/cancel` | customer | pay (simulated processor); cancel until the kitchen accepts, refunded if paid |
| `GET` | `/orders/stream` | customer | Server-Sent Events for the customer's orders |
| `GET` | `/kitchen/restaurants/{id}/orders`, `…/stream` | staff | board + amount owed from the ledger; live stream |
| `POST` | `/kitchen/orders/{id}/{accept\|ready\|complete\|reject}` | staff | 409 if the order moved meanwhile |
| `POST` | `/payments/webhook` | processor | authenticated by `X-Payment-Signature` (HMAC), not a session |

## Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL`, `DATABASE_PORT`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `localhost`, `5432`, `postgres`, `secret` | PostgreSQL |
| `INIT_DB` | `always` | `always` rebuilds the schema and sample data at startup; `never` keeps data |
| `CACHE_TYPE` | `redis` | `simple` for an in-memory menu cache (no Redis) |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | |
| `ORDER_PAY_WITHIN_SECONDS` | `900` | unpaid orders are cancelled after this |
| `PAYMENT_WEBHOOK_SECRET` | development value | shared secret for webhook signatures; required when `APP_DEMO=false` |
| `APP_DEMO` | `true` | demo accounts and the simulated card processor; set `false` anywhere real |
| `BACKGROUND_JOBS` | `true` | outbox dispatcher, expiry sweeper, live-update listener |

## History

This started as a course-style DoorDash clone (menus, cart, Redis-cached menus, `@Version` on the cart), deployed once on AWS App Runner + RDS; that deployment has been shut down. The ordering, inventory, payment and platform modules, the integration tests and the new interface came later.

## License

MIT
