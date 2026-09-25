package com.laioffer.onlineorder.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Delivers outbox messages to their handlers.
 *
 * <p>Two steps per pass:
 * <ol>
 *   <li><b>Claim</b> (one short transaction): pick due rows with {@code FOR UPDATE SKIP LOCKED}
 *       and push their {@code available_at} forward by a lease. Any number of instances can run
 *       this at once; each gets different rows and nobody waits. Commit immediately.</li>
 *   <li><b>Deliver</b> each claimed message on its own. A database-only handler runs in the
 *       transaction that marks the row delivered (exactly-once). A handler that calls another
 *       system runs outside any transaction, then the row is marked (at-least-once: a crash in
 *       between redelivers after the lease expires; the handler passes the outbox id as the
 *       remote idempotency key).</li>
 * </ol>
 * A failure reschedules only that message, with exponential backoff (capped at 10 minutes), and
 * keeps the error for an operator.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    static final int LEASE_SECONDS = 60;

    private record Claimed(long id, String topic, String payload, int attempts) {
    }

    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final ObjectMapper json;
    private final OutboxHandlers handlers;

    public OutboxDispatcher(JdbcTemplate jdbc, Tx tx, ObjectMapper json, OutboxHandlers handlers) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-ms:500}")
    public void poll() {
        try {
            while (runOnce(50) == 50) {
                // keep draining while batches come back full
            }
        } catch (RuntimeException e) {
            log.warn("outbox pass failed", e);
        }
    }

    /** Claims and delivers up to {@code limit} due messages; returns how many were claimed. */
    public int runOnce(int limit) {
        List<Claimed> batch = tx.run(() -> jdbc.query("""
                UPDATE outbox SET available_at = now() + make_interval(secs => ?)
                WHERE id IN (
                    SELECT id FROM outbox
                    WHERE processed_at IS NULL AND available_at <= now()
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, topic, payload::text, attempts
                """, (rs, i) -> new Claimed(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                LEASE_SECONDS, limit));
        for (Claimed m : batch) {
            deliver(m);
        }
        return batch.size();
    }

    private void deliver(Claimed m) {
        OutboxTopicHandler handler = handlers.forTopic(m.topic());
        try {
            if (handler == null) {
                throw new IllegalStateException("no handler for topic " + m.topic());
            }
            JsonNode payload = parse(m.payload());
            if (handler.transactional()) {
                tx.runVoid(() -> {
                    handler.handle(m.id(), payload);
                    markDelivered(m.id());
                });
            } else {
                handler.handle(m.id(), payload); // no transaction open while calling out
                markDelivered(m.id());
            }
        } catch (RuntimeException e) {
            reschedule(m, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable payload", e);
        }
    }

    private void markDelivered(long id) {
        jdbc.update("UPDATE outbox SET processed_at = now() WHERE id = ?", id);
    }

    private void reschedule(Claimed m, String error) {
        int attempts = m.attempts() + 1;
        long delaySeconds = Math.min(600, (long) Math.pow(2, Math.min(attempts, 10)));
        jdbc.update("""
                UPDATE outbox
                SET attempts = ?, last_error = ?, available_at = now() + make_interval(secs => ?)
                WHERE id = ?
                """, attempts, error, delaySeconds, m.id());
        log.warn("outbox {} ({}) failed, attempt {}, retry in {}s: {}", m.id(), m.topic(), attempts, delaySeconds, error);
    }
}
