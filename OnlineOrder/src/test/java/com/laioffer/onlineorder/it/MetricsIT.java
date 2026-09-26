package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.platform.BusinessMetrics;
import com.laioffer.onlineorder.platform.OperationalGauges;
import com.laioffer.onlineorder.platform.OrderUpdatesHub;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsIT extends PostgresTestSupport {

    SimpleMeterRegistry registry;

    @BeforeEach
    void registry() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
    }

    double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void aCounterInsideARolledBackTransactionDoesNotCount() {
        assertThrows(IllegalStateException.class, () -> tx.runVoid(() -> {
            BusinessMetrics.countOnCommit("probe");
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, counter("probe"));

        tx.runVoid(() -> BusinessMetrics.countOnCommit("probe"));
        assertEquals(1, counter("probe"));
    }

    @Test
    void aFailedCheckoutCountsNoOrderAndAPaidOneCountsTwoTransitions() {
        long c = customer("metrics@test");
        setStock(2, 0);
        addToCart(c, 2, 1);
        assertThrows(RuntimeException.class, () -> checkout.checkout(c, null, null)); // out of stock
        assertEquals(0, counter("orders.transitions", "to", "PLACED"));

        jdbc.update("DELETE FROM order_items");
        paidOrder(c, 1, 1);
        assertEquals(1, counter("orders.transitions", "to", "PLACED"));
        assertEquals(1, counter("orders.transitions", "from", "PLACED", "to", "PAID"));
        assertEquals(1, counter("payments.webhooks", "outcome", "processed"));
    }

    @Test
    void gaugesReportStuckOutboxMessagesAndOverdueOrders() {
        OrderUpdatesHub hub = new OrderUpdatesHub(dataSource,
                new StaticListableBeanFactory().getBeanProvider(DataSourceProperties.class), json, false);
        OperationalGauges gauges = new OperationalGauges(jdbc, registry, hub);
        jdbc.update("INSERT INTO outbox (topic, payload, attempts, created_at) VALUES ('t', '{}', 7, now() - interval '5 minutes')");
        jdbc.update("INSERT INTO outbox (topic, payload) VALUES ('t', '{}')");
        long c = customer("overdue@test");
        addToCart(c, 1, 1);
        long orderId = checkout.checkout(c, null, null).id();
        jdbc.update("UPDATE orders SET pay_by = now() - interval '10 minutes' WHERE id = ?", orderId);

        gauges.refresh();
        gauges.checkLedger();

        assertEquals(3, registry.get("outbox.pending").gauge().value()); // + the order's notification
        assertEquals(1, registry.get("outbox.stuck").gauge().value());
        assertEquals(300, registry.get("outbox.oldest.pending.age").gauge().value(), 5);
        assertEquals(1, registry.get("orders.overdue.unpaid").gauge().value());
        assertEquals(0, registry.get("ledger.unbalanced.transactions").gauge().value());
    }

    /** A broken metrics backend must not turn a committed checkout into an error for the customer. */
    @Test
    void aFailingMetricsRegistryDoesNotFailACommittedCheckout() {
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean();
        // Every counter of this registry throws on increment once armed (a backend gone wrong).
        io.micrometer.core.instrument.MeterRegistry broken = new SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                io.micrometer.core.instrument.Counter real = super.newCounter(id);
                return new io.micrometer.core.instrument.Counter() {
                    @Override
                    public void increment(double amount) {
                        if (failing.get()) {
                            throw new IllegalStateException("metrics backend is broken");
                        }
                        real.increment(amount);
                    }

                    @Override
                    public double count() {
                        return real.count();
                    }

                    @Override
                    public io.micrometer.core.instrument.Meter.Id getId() {
                        return id;
                    }
                };
            }
        };
        Metrics.addRegistry(broken);
        failing.set(true);
        try {
            long c = customer("brokenmetrics@test");
            addToCart(c, 1, 1);
            long orderId = checkout.checkout(c, null, null).id();
            assertEquals(1, count("SELECT count(*) FROM orders WHERE id = ?", orderId));
        } finally {
            Metrics.removeRegistry(broken);
        }
    }
}
