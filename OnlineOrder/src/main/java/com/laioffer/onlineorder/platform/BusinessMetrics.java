package com.laioffer.onlineorder.platform;

import io.micrometer.core.instrument.Metrics;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counters for business events (orders placed, paid, cancelled; webhooks; outbox deliveries).
 *
 * <p>A counter must agree with the database: a checkout that rolls back placed no order. Inside a
 * transaction the increment is therefore deferred to after COMMIT and dropped on rollback;
 * outside one it happens immediately.
 *
 * <p>Uses Micrometer's global registry, which Spring Boot connects to the Prometheus registry.
 * Services built by hand (the integration tests) need no wiring; their counts simply go nowhere.
 */
public final class BusinessMetrics {

    private BusinessMetrics() {
    }

    /** Increments counter {@code name} with {@code tags} (key, value, key, value, ...) once committed. */
    public static void countOnCommit(String name, String... tags) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count(name, tags);
                }
            });
        } else {
            count(name, tags);
        }
    }

    public static void count(String name, String... tags) {
        Metrics.counter(name, tags).increment();
    }
}
