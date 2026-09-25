# Operations

How to know the system is healthy, what each alert means, and what to do about it. The alert
rules are in [`deploy/prometheus/alerts.yml`](../deploy/prometheus/alerts.yml); each one links
to a section below. The dashboard is [`deploy/grafana/dashboards/online-order.json`](../deploy/grafana/dashboards/online-order.json).
`docker compose up --build` runs both locally next to the app.

## Service level objectives

| SLO | Target | Measured by | Why this target |
|---|---|---|---|
| Checkout latency | p99 < 300 ms over 30 days | `http_server_requests_seconds` histogram, `POST /orders` | SCALING.md measured p99 ≈ 110 ms for a flash sale at 32 concurrent buyers on 2 vCPUs; 300 ms leaves room for the network and a busier database |
| Availability | 99.9% of requests are not 5xx over 30 days | `http_server_requests_seconds_count` by status | about 43 minutes of errors a month. 4xx are the customer's (sold out, price changed) and do not count |
| Money correctness | 0 unbalanced ledger transactions, always | `ledger_unbalanced_transactions` | not a budget: a single occurrence is an incident |
| Side effects | every refund and notification delivered within 5 minutes | `outbox_oldest_pending_age_seconds` | a refund that silently never reaches the processor is money kept that should not be |

`ErrorBudgetBurn` uses a multi-window burn rate: it pages when the last hour **and** the last
5 minutes both spend the budget 14.4 times faster than sustainable (2% of the month's budget
in an hour), so a short blip does not page and a real outage pages within minutes.

## Where to look first

1. **Grafana, top row**: the four correctness guards. All should be 0 (or seconds, for the outbox age).
2. **Logs** are JSON in containers (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`). Every line of a
   request carries `traceId`; a customer's error response carries the same id in `X-Trace-Id`.
3. **Health**: `GET :8081/actuator/health` shows the database status; `/health/readiness` is what
   the load balancer uses.
4. **The database** is the source of truth for everything below. The queries are safe to run on
   the primary.

## Alerts and runbooks

### Checkout latency high

`POST /orders` p99 above 300 ms for 10 minutes.

- Check `hikaricp_connections_pending` (Capacity row). Waiting for a connection means the pool
  or the database is the limit, see [Connection pool saturated](#connection-pool-saturated).
- Check whether one limited dish is being bought by everyone at once (a flash sale): the lock
  on its inventory row serialises checkouts (SCALING.md). `api_errors_total{code="OUT_OF_STOCK"}`
  climbing at the same time confirms it. It resolves when the dish sells out; the lasting fix
  is splitting the stock into bucket rows (SCALING.md, "Where it goes next").
- Look for long transactions holding locks:
  ```sql
  SELECT pid, now() - xact_start AS age, state, left(query, 80)
  FROM pg_stat_activity WHERE xact_start IS NOT NULL ORDER BY age DESC LIMIT 10;
  ```

### Error budget burn

5xx rate is spending the 99.9% budget 14 times too fast.

- Find the route: dashboard "Requests per second by route", or
  `sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))`.
- Take a trace id from a failing response or an ERROR log line and follow it.
- Started right after a deploy: roll back (below), then investigate.
- Database errors in every route: check RDS status and failover events. During a failover,
  requests fail for 60–120 s and clients retry; checkout retries are safe (Idempotency-Key).

### Outbox backlog growing

The oldest undelivered outbox message is older than 5 minutes. Refunds and notifications are
waiting.

- Are the dispatchers running? `BACKGROUND_JOBS` must be `true` on at least one instance, and
  the logs show `outbox pass failed` warnings if the claim query itself fails.
- What is waiting, and why:
  ```sql
  SELECT topic, count(*), min(created_at), max(attempts), max(last_error)
  FROM outbox WHERE processed_at IS NULL GROUP BY topic;
  ```
- If one topic's handler fails every time (e.g. the processor is down), its messages back off
  up to 10 minutes each while the other topics keep flowing. Nothing is lost; they are
  delivered when the dependency recovers.

### Outbox messages stuck

Messages have failed 5 or more times.

- `SELECT id, topic, attempts, last_error, payload FROM outbox WHERE processed_at IS NULL AND attempts >= 5;`
- A `refund` stuck here is money owed to a customer. Fix the cause (credentials, a changed
  processor API), and the next retry delivers it. Refunds carry `refund-<outbox id>` as the
  processor's idempotency key, so a retry after an ambiguous failure cannot refund twice.
- To retry now instead of waiting for the backoff:
  `UPDATE outbox SET available_at = now() WHERE id = <id>;`
- Never delete an undelivered refund row to silence the alert.

### Unpaid orders not expiring

Orders more than a minute past `pay_by` are still `PLACED`, so their stock is still held and
customers see dishes as sold out.

- Is the sweeper running? It runs on instances with `BACKGROUND_JOBS=true` every 5 s and logs
  `expired N unpaid orders` or `expiry sweep failed`.
- A cancellation that keeps failing (e.g. the inventory release) rolls back the whole batch;
  the error is in the `expiry sweep failed` log line.
- `SELECT id, pay_by, created_at FROM orders WHERE status = 'PLACED' AND pay_by < now() ORDER BY pay_by LIMIT 20;`

### Ledger unbalanced

A ledger transaction's entries do not sum to zero. The deferred database trigger should make
this impossible, so treat it as an incident: either the trigger was dropped or bypassed, or
data was changed by hand.

```sql
SELECT txn_id, sum(amount_cents), array_agg(account || ' ' || amount_cents)
FROM ledger_entries GROUP BY txn_id HAVING sum(amount_cents) <> 0;

SELECT tgname, tgenabled FROM pg_trigger WHERE tgrelid = 'ledger_entries'::regclass;
```

Entries are append-only (a second trigger forbids UPDATE and DELETE), so correct with a new,
balancing transaction; never edit rows. Freeze payouts to restaurants until it is understood.

### Webhook signature failures

Payment webhooks are being rejected with 401 (`payments_webhooks_total{outcome="bad_signature"}`).

- Right after rotating `PAYMENT_WEBHOOK_SECRET`: the processor and the service disagree on the
  secret. Webhooks for real payments are being refused, so orders stay unpaid until they expire.
  Fix the secret on whichever side is wrong; processors retry deliveries for hours.
- `BAD_SIGNATURE` can also mean a stale timestamp (older than 5 minutes): check the clocks.
- Without a rotation: someone is sending forged webhooks. They are refused; no action beyond
  noting the source addresses (WAF).

### Connection pool saturated

Requests are waiting for a database connection.

- More instances do not help if the database is the limit; check its CPU and
  `pg_stat_activity` first.
- Each instance holds a pool (Hikari default 10) plus one LISTEN connection. Instances × pool
  must stay below the database's `max_connections`; in AWS, RDS Proxy multiplexes (CLOUD.md).
- A slow query holding connections shows up in the query above with a large `age`.

### Instance down

Prometheus cannot scrape an instance for 2 minutes. The orchestrator should already be
replacing it (the container's health check and the load balancer both use
`/actuator/health/readiness`). If it keeps restarting, the reason is in the last log lines
before the exit: a failed migration, a missing secret (the app refuses to start without one
when demo mode is off), or `ExitOnOutOfMemoryError`.

## Deploying and rolling back

- **Deploy**: CI builds and tests the image; a rolling deploy starts new tasks, which run
  Flyway migrations, pass readiness, and then receive traffic. Old tasks get SIGTERM, stop
  accepting requests, finish in-flight ones (graceful shutdown, 20 s), close their SSE streams
  so browsers reconnect to a new task, and exit. Sessions are in the database, so nobody is
  signed out by a deploy.
- **Roll back** by deploying the previous image. This is safe because every migration is
  expand-only (ARCHITECTURE.md, ADR 9): the previous version runs against the newer schema.
  Flyway never runs "down" migrations; a destructive change is its own later release.
- **After a deploy**, watch the error-rate and latency panels for 15 minutes, and the
  correctness row.

## Configuration reference

| Variable | Default | |
|---|---|---|
| `APP_DEMO` | `true` (`false` in the Docker image) | demo accounts and the simulated card processor |
| `DB_RESET_ON_START` | `false` | wipe and re-migrate at startup; refused unless `APP_DEMO=true` |
| `FLYWAY_LOCATIONS` | `classpath:db/migration,classpath:db/seed` | drop `db/seed` where the menu is managed for real |
| `PAYMENT_WEBHOOK_SECRET` | development value | required when `APP_DEMO=false` |
| `BACKGROUND_JOBS` | `true` | outbox dispatcher, expiry sweeper, gauges, live-update listener |
| `MANAGEMENT_PORT` | `8081` | actuator (health, Prometheus); keep it off the public listener |
| `TRACING_SAMPLE_RATE` | `0.1` | share of requests traced |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | unset | e.g. `http://otel-collector:4318/v1/traces`; unset = not exported |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | unset (`ecs` in the image) | JSON logs |
| `RATE_LIMIT_ENABLED` | `true` | also `RATE_LIMIT_LOGIN_PER_MINUTE` (10), `…_LOGIN_PER_ACCOUNT_PER_MINUTE` (20), `…_SIGNUP_PER_MINUTE` (5), `…_CHECKOUT_PER_MINUTE` (30) |
| `SHUTDOWN_GRACE` | `20s` | how long in-flight requests get on SIGTERM |
| `SESSION_TIMEOUT` | `8h` | idle time before signing in again; sessions are stored in PostgreSQL |
| `SESSION_COOKIE_SECURE` | `false` | set `true` wherever the site is served over HTTPS |
| `SPRING_PROFILES_ACTIVE=dev` | | logs SQL and raw requests; never in production |
