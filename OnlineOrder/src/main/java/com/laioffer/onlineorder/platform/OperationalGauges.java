package com.laioffer.onlineorder.platform;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges for the things that go wrong quietly: an outbox that stops draining, messages that keep
 * failing, unpaid orders the sweeper missed, a ledger that does not balance. Each one has an
 * alert and a runbook entry in docs/OPERATIONS.md.
 *
 * <p>The values come from the database, so they describe the whole system, not this instance:
 * every instance reports the same number and dashboards aggregate with {@code max}. They are
 * refreshed on a schedule rather than per scrape, so a scrape never waits on a query. The ledger
 * check scans every entry and runs far less often.
 */
@Component
public class OperationalGauges {

    private static final Logger log = LoggerFactory.getLogger(OperationalGauges.class);

    /** Attempts after which an outbox message counts as stuck (backoff is then over 30 s). */
    static final int STUCK_AFTER_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();
    private final AtomicLong outboxStuck = new AtomicLong();
    private final AtomicLong ordersOverdueUnpaid = new AtomicLong();
    private final AtomicLong ledgerUnbalanced = new AtomicLong();

    public OperationalGauges(JdbcTemplate jdbc, MeterRegistry registry, OrderUpdatesHub hub) {
        this.jdbc = jdbc;
        Gauge.builder("outbox.pending", outboxPending, AtomicLong::get)
                .description("Outbox messages not yet delivered").register(registry);
        Gauge.builder("outbox.oldest.pending.age", outboxOldestAgeSeconds, AtomicLong::get)
                .baseUnit("seconds").description("Age of the oldest undelivered outbox message").register(registry);
        Gauge.builder("outbox.stuck", outboxStuck, AtomicLong::get)
                .description("Undelivered outbox messages that failed " + STUCK_AFTER_ATTEMPTS + "+ times").register(registry);
        Gauge.builder("orders.overdue.unpaid", ordersOverdueUnpaid, AtomicLong::get)
                .description("Unpaid orders more than a minute past pay_by (the sweeper should have cancelled them)")
                .register(registry);
        Gauge.builder("ledger.unbalanced.transactions", ledgerUnbalanced, AtomicLong::get)
                .description("Ledger transactions whose entries do not sum to zero (must always be 0)").register(registry);
        Gauge.builder("sse.subscribers", hub, OrderUpdatesHub::subscriberCount)
                .description("Open Server-Sent Events streams on this instance").register(registry);
    }

    @Scheduled(fixedDelayString = "${metrics.gauges.refresh-ms:15000}", initialDelay = 0)
    public void refresh() {
        try {
            Map<String, Object> outbox = jdbc.queryForMap("""
                    SELECT count(*) AS pending,
                           count(*) FILTER (WHERE attempts >= ?) AS stuck,
                           COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)::bigint AS oldest_age
                    FROM outbox WHERE processed_at IS NULL
                    """, STUCK_AFTER_ATTEMPTS);
            outboxPending.set(((Number) outbox.get("pending")).longValue());
            outboxStuck.set(((Number) outbox.get("stuck")).longValue());
            outboxOldestAgeSeconds.set(((Number) outbox.get("oldest_age")).longValue());
            // Uses the partial index on (pay_by) WHERE status = 'PLACED'.
            ordersOverdueUnpaid.set(jdbc.queryForObject(
                    "SELECT count(*) FROM orders WHERE status = 'PLACED' AND pay_by < now() - interval '1 minute'",
                    Long.class));
        } catch (RuntimeException e) {
            log.warn("refreshing operational gauges failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${metrics.ledger-check-ms:3600000}", initialDelay = 0)
    public void checkLedger() {
        try {
            ledgerUnbalanced.set(jdbc.queryForObject("""
                    SELECT count(*) FROM (
                        SELECT txn_id FROM ledger_entries GROUP BY txn_id HAVING sum(amount_cents) <> 0) t
                    """, Long.class));
        } catch (RuntimeException e) {
            log.warn("ledger balance check failed", e);
        }
    }
}
