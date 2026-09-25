package com.laioffer.onlineorder.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes rows that have done their job, so the outbox and key tables stay small. */
@Component
public class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final JdbcTemplate jdbc;

    public Housekeeping(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${housekeeping.interval-ms:3600000}", initialDelay = 60_000)
    public void prune() {
        try {
            int outbox = jdbc.update("DELETE FROM outbox WHERE processed_at < now() - interval '7 days'");
            // A key only needs to outlive the client's retries.
            int keys = jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'");
            if (outbox + keys > 0) {
                log.info("housekeeping: deleted {} delivered outbox rows, {} expired idempotency keys", outbox, keys);
            }
        } catch (RuntimeException e) {
            log.warn("housekeeping failed", e);
        }
    }
}
