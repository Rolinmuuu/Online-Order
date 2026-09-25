package com.laioffer.onlineorder.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.inventory.InventoryService;
import com.laioffer.onlineorder.ordering.CheckoutService;
import com.laioffer.onlineorder.ordering.NotificationHandler;
import com.laioffer.onlineorder.ordering.OrderCancelled;
import com.laioffer.onlineorder.ordering.OrderService;
import com.laioffer.onlineorder.ordering.Orders;
import com.laioffer.onlineorder.payment.Ledger;
import com.laioffer.onlineorder.payment.PaymentProvider;
import com.laioffer.onlineorder.payment.PaymentService;
import com.laioffer.onlineorder.payment.RefundHandler;
import com.laioffer.onlineorder.payment.WebhookSignature;
import com.laioffer.onlineorder.platform.OrderUpdates;
import com.laioffer.onlineorder.platform.Outbox;
import com.laioffer.onlineorder.platform.OutboxDispatcher;
import com.laioffer.onlineorder.platform.OutboxHandlers;
import com.laioffer.onlineorder.platform.Tx;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests run against a real PostgreSQL, because the guarantees under test (row locks,
 * conditional updates, unique constraints, deferred triggers, NOTIFY on commit) are the
 * database's. Each test starts from a fresh schema loaded from database-init.sql through the
 * same script runner Spring Boot uses at startup.
 *
 * <p>Connection: TEST_DATABASE_URL (default jdbc:postgresql://localhost:5432/onlineorder_test — a
 * database of its own: the schema is dropped and rebuilt for every test),
 * TEST_DATABASE_USER (postgres), TEST_DATABASE_PASSWORD (secret) — the values CI and
 * docker-compose use.
 */
abstract class PostgresTestSupport {

    static HikariDataSource dataSource;
    static final String SECRET = "test-webhook-secret";

    JdbcTemplate jdbc;
    Tx tx;
    ObjectMapper json;
    Orders orders;
    InventoryService inventory;
    CheckoutService checkout;
    OrderService orderService;
    Ledger ledger;
    PaymentService payments;
    RecordingProvider provider;
    WebhookSignature signature;
    OutboxDispatcher dispatcher;

    /** Records calls instead of charging; tests deliver webhooks themselves. */
    static class RecordingProvider implements PaymentProvider {
        final List<String> charges = new CopyOnWriteArrayList<>();
        final Map<String, String> refunds = new ConcurrentHashMap<>();
        final AtomicInteger refundCalls = new AtomicInteger();

        @Override
        public void charge(String paymentRef, long amountCents) {
            charges.add(paymentRef);
        }

        @Override
        public void refund(String paymentRef, long amountCents, String idempotencyKey) {
            refundCalls.incrementAndGet();
            refunds.putIfAbsent(idempotencyKey, paymentRef);
        }
    }

    @BeforeAll
    static void connect() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(env("TEST_DATABASE_URL", "jdbc:postgresql://localhost:5432/onlineorder_test"));
        c.setUsername(env("TEST_DATABASE_USER", "postgres"));
        c.setPassword(env("TEST_DATABASE_PASSWORD", "secret"));
        c.setMaximumPoolSize(60);
        dataSource = new HikariDataSource(c);
    }

    @AfterAll
    static void disconnect() {
        dataSource.close();
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    @BeforeEach
    void freshSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("database-init.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new Tx(new DataSourceTransactionManager(dataSource));
        json = new ObjectMapper();
        OrderUpdates updates = new OrderUpdates(jdbc, json);
        Outbox outbox = new Outbox(jdbc, json);
        orders = new Orders(jdbc, outbox, updates);
        inventory = new InventoryService(jdbc);
        checkout = new CheckoutService(jdbc, tx, orders, inventory, 900);
        ledger = new Ledger(jdbc);
        provider = new RecordingProvider();
        signature = new WebhookSignature(SECRET, Clock.systemUTC());
        PaymentService[] ref = new PaymentService[1];
        orderService = new OrderService(jdbc, tx, orders, inventory,
                event -> ref[0].onOrderCancelled((OrderCancelled) event));
        payments = new PaymentService(jdbc, tx, orders, ledger, outbox, provider, signature, json);
        ref[0] = payments;
        dispatcher = new OutboxDispatcher(jdbc, tx, json,
                new OutboxHandlers(List.of(new NotificationHandler(jdbc), new RefundHandler(provider))));
    }

    // ───────── fixtures ─────────

    /** A customer with an empty cart; returns the customer id. */
    long customer(String email) {
        long id = jdbc.queryForObject(
                "INSERT INTO customers (email, password, enabled) VALUES (?, 'x', true) RETURNING id", Long.class, email);
        jdbc.update("INSERT INTO carts (customer_id, total_price) VALUES (?, 0)", id);
        return id;
    }

    void addToCart(long customerId, long menuItemId, int quantity) {
        jdbc.update("""
                INSERT INTO order_items (menu_item_id, cart_id, price, quantity)
                SELECT ?, c.id, m.price, ? FROM carts c, menu_items m WHERE c.customer_id = ? AND m.id = ?
                """, menuItemId, quantity, customerId, menuItemId);
    }

    void setStock(long menuItemId, int available) {
        jdbc.update("""
                INSERT INTO inventory (menu_item_id, available) VALUES (?, ?)
                ON CONFLICT (menu_item_id) DO UPDATE SET available = EXCLUDED.available
                """, menuItemId, available);
    }

    int stock(long menuItemId) {
        return jdbc.queryForObject("SELECT available FROM inventory WHERE menu_item_id = ?", Integer.class, menuItemId);
    }

    void staff(String email, long restaurantId) {
        if (count("SELECT count(*) FROM customers WHERE email = ?", email) == 0) {
            customer(email);
        }
        jdbc.update("INSERT INTO restaurant_staff (email, restaurant_id) VALUES (?, ?)", email, restaurantId);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Places and pays an order for {@code quantity} × item; returns the order id. */
    long paidOrder(long customerId, long menuItemId, int quantity) {
        addToCart(customerId, menuItemId, quantity);
        long orderId = checkout.checkout(customerId, null, null).id();
        PaymentService.PaymentView p = payments.startPayment(orderId, customerId);
        payments.handleWebhook(succeeded(p), signature.sign(succeeded(p)));
        return orderId;
    }

    private final Map<String, String> eventIds = new ConcurrentHashMap<>();

    /** The provider's "payment.succeeded" event for this payment (same event id every time). */
    String succeeded(PaymentService.PaymentView p) {
        String id = eventIds.computeIfAbsent(p.paymentRef(), r -> "evt_" + r);
        return "{\"id\":\"" + id + "\",\"type\":\"payment.succeeded\",\"payment_ref\":\"" + p.paymentRef()
                + "\",\"amount_cents\":" + p.amountCents() + "}";
    }

    // ───────── concurrency helper ─────────

    /** Runs all tasks at the same instant on separate threads; returns each result or exception. */
    static <T> List<Object> race(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return t.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<T> f : futures) {
            try {
                out.add(f.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                out.add(e.getCause() != null ? e.getCause() : e);
            }
        }
        pool.shutdownNow();
        return out;
    }
}
