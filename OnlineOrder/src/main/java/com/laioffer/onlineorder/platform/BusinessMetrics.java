package com.laioffer.onlineorder.platform;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counters for business events (orders placed, paid, cancelled; webhooks; outbox deliveries).
 *
 * <p>A counter must agree with the database: a checkout that rolls back placed no order. Inside a
 * transaction the increment is therefore deferred to after COMMIT and dropped on rollback;
 * outside one it happens immediately.
 *
 * <p>Recording a metric never fails the business operation: an exception from the metrics
 * library is logged and swallowed. This matters most after COMMIT, where an exception would
 * reach the caller of a transaction that did commit (a placed order reported as failed).
 *
 * <p>Uses Micrometer's global registry, which Spring Boot connects to the Prometheus registry.
 * Services built by hand (the integration tests) need no wiring; their counts simply go nowhere.
 */
public final class BusinessMetrics {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);

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
        try {
            Metrics.counter(name, tags).increment();
        } catch (RuntimeException e) {
            log.warn("could not record metric {}", name, e);
        }
    }
}
