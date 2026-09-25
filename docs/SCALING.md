# Bottlenecks and measurements

Everything below was measured, not estimated. The method and the machine are stated so the
numbers can be reproduced.

## Checkout under contention

**Setup.** `CheckoutBenchmark` (test sources) runs the real `CheckoutService` against PostgreSQL
16 with no HTTP in the loop. N threads each check out a one-item cart; 2,000 orders per
scenario; fresh schema for each scenario. **Three interleaved rounds**, so machine drift hits
every variant alike. Machine: a 2-vCPU, 8 GB Linux container, with the application and
PostgreSQL on the **same** two cores. Absolute numbers are therefore low; compare the rows with
each other.

Medians of the three rounds (range in brackets):

| Scenario | Threads | Orders/s | p95 ms | p99 ms |
|---|---|---|---|---|
| unlimited dish: no shared row | 8 | 1,419 (1,404–1,517) | 9.8 (8.5–10.9) | 14.0 |
| hot dish, stock reserved **last** (what the code does) | 8 | 1,292 (1,243–1,329) | 10.1 (9.4–11.6) | 12.6 |
| hot dish, stock reserved **first** | 8 | 725 (718–819) | 29.8 (25.1–30.4) | 45.9 |
| unlimited dish | 32 | 1,321 (1,087–1,343) | 39.0 (37.8–53.7) | 55.7 |
| hot dish, stock **last** | 32 | 884 (862–932) | 105 (100–111) | 158 |
| hot dish, stock **first** | 32 | 632 (574–643) | 175 (167–178) | 270 |
| flash sale: 500 units, 2,000 buyers, stock last | 32 | 1,334 (1,281–1,380) | 64.6 (62.9–66.4) | 109.6 |
| any variant, 1 thread | 1 | 630–711 | ≈2 | ≈3 |

"Hot dish" means every order contains the same limited item, so every checkout needs the same
inventory row lock. Each run checked that nothing was oversold (units sold ≤ stock, stock never
negative).

**Reading it.**

1. **Where the lock is taken matters more than whether there is one.** With the stock reserved
   last, a hot dish costs about 10% throughput at 8 threads and p95 barely moves. Reserved first,
   the same work loses about half its throughput and p95 triples. A row lock is held until
   COMMIT, so taking it last means holding it only for the tail of the transaction (ADR 3).
2. **At 32 concurrent checkouts the hot row is the bottleneck either way.** Taking it last still
   gives about 40% more throughput than taking it first. Against an uncontended dish, throughput
   is about a third lower and p95 is 2.5× higher, because transactions queue on one lock.
3. **A flash sale where most buyers lose is fast.** 1,500 of 2,000 checkouts fail on the
   conditional update and roll back; their earlier inserts are discarded. With the stock taken
   last they still do those inserts first, which is the price of shorter lock holding for the
   winners.
4. **Not measured here:** every order change also runs `pg_notify`, and PostgreSQL serialises
   commits that sent a notification on a global queue lock. At these rates it didn't show; at
   much higher commit rates it could, and the fix would be to notify from the outbox dispatcher
   instead of the business transaction.

## Where it goes next, in order

| Limit | Symptom | Next step |
|---|---|---|
| One inventory row per dish | lock queue at 32+ concurrent buyers of one dish (above) | split a dish's stock into K bucket rows and pick a random bucket with stock; K× less contention, and the total is a SUM |
| Connections | each request holds a pooled connection for its transaction; each instance also keeps one dedicated LISTEN connection | PgBouncer / RDS Proxy in transaction mode for request traffic; LISTEN on a direct connection |
| Single primary | all writes on one node | read replicas for menus and history first; partition `orders` by restaurant or time before considering sharding |
| Outbox polling | ~0.5 s delay, one query per 500 ms per instance | lower the interval, or wake the dispatcher with NOTIFY; beyond thousands of messages per second, relay the outbox to Kafka |
| Kitchen board query | reads every active order for a restaurant | fine at restaurant scale; cursor pagination if a kitchen ever has hundreds open |

## Reproduce

Run `com.laioffer.onlineorder.it.CheckoutBenchmark.main` (test sources; optional argument: orders
per scenario, default 2000) from the IDE or on the test classpath, with `TEST_DATABASE_URL`
pointing at a **disposable** database: the schema is dropped and recreated for every scenario.
