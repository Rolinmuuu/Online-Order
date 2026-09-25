package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.ordering.OrderStatus;
import com.laioffer.onlineorder.ordering.OrderView;
import com.laioffer.onlineorder.platform.ApiException;
import com.laioffer.onlineorder.platform.OutOfStock;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutIT extends PostgresTestSupport {

    @Test
    void checkoutSnapshotsPricesInCentsAndEmptiesTheCart() {
        long c = customer("a@test");
        addToCart(c, 1, 2);   // Double Smash Burger 12.50
        addToCart(c, 4, 1);   // Rosemary Fries 4.75

        OrderView o = checkout.checkout(c, null, 2975L);

        assertEquals(OrderStatus.PLACED, o.status());
        assertEquals(2975, o.totalCents());
        assertEquals(2, o.lines().size());
        assertEquals(1250, o.lines().get(0).unitPriceCents());
        assertEquals("PLACED", o.events().get(0).toStatus());
        long payWindow = Duration.between(Instant.now(), o.payBy()).toSeconds();
        assertTrue(payWindow > 880 && payWindow <= 900, "pay window " + payWindow);
        assertEquals(0, count("SELECT count(*) FROM order_items"));
        assertEquals(1, count("SELECT count(*) FROM outbox WHERE topic = 'order.notification'"));

        // A later menu price change does not touch the order.
        jdbc.update("UPDATE menu_items SET price = 99 WHERE id = 1");
        assertEquals(2975, orders.view(o.id()).totalCents());
    }

    @Test
    void twentyConcurrentRetriesWithOneKeyCreateOneOrder() throws Exception {
        long c = customer("retry@test");
        addToCart(c, 1, 1);
        List<Callable<OrderView>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            attempts.add(() -> checkout.checkout(c, "key-1", 1250L));
        }
        List<Object> results = race(attempts);
        assertTrue(results.stream().allMatch(r -> r instanceof OrderView), results.toString());
        assertEquals(1, results.stream().map(r -> ((OrderView) r).id()).distinct().count());
        assertEquals(1, count("SELECT count(*) FROM orders"));
    }

    @Test
    void aKeyReusedForADifferentRequestIsRejected() {
        long c = customer("k@test");
        addToCart(c, 1, 1);
        checkout.checkout(c, "key-1", 1250L);
        addToCart(c, 4, 1);
        ApiException e = assertThrows(ApiException.class, () -> checkout.checkout(c, "key-1", 475L));
        assertEquals(422, e.status().value());
    }

    @Test
    void aFailedCheckoutDoesNotBurnItsKey() {
        setStock(2, 0);
        long c = customer("f@test");
        addToCart(c, 2, 1);
        assertThrows(OutOfStock.class, () -> checkout.checkout(c, "key-1", 1600L));
        setStock(2, 5);   // restocked
        OrderView o = checkout.checkout(c, "key-1", 1600L);
        assertEquals(OrderStatus.PLACED, o.status());
    }

    @Test
    void aPriceChangeSinceTheCartWasShownIsAConflict() {
        long c = customer("p@test");
        addToCart(c, 1, 1);
        jdbc.update("UPDATE menu_items SET price = 13.00 WHERE id = 1");
        ApiException e = assertThrows(ApiException.class, () -> checkout.checkout(c, null, 1250L));
        assertEquals("PRICE_CHANGED", e.code());
        assertEquals(1, count("SELECT count(*) FROM order_items"), "cart untouched");
    }

    @Test
    void thePriceChangedErrorCarriesTheNewTotalSoTheClientCanConfirmIt() {
        long c = customer("p2@test");
        addToCart(c, 1, 2);
        jdbc.update("UPDATE menu_items SET price = 13.00 WHERE id = 1");
        com.laioffer.onlineorder.platform.PriceChanged e =
                assertThrows(com.laioffer.onlineorder.platform.PriceChanged.class, () -> checkout.checkout(c, null, 2500L));
        assertEquals(2600, e.totalCents());
        assertEquals(2600, checkout.checkout(c, null, e.totalCents()).totalCents());
    }

    /**
     * Add-to-cart (CartService via Spring Data JDBC) reads the cart, updates the item row by id,
     * then saves the cart with its @Version. Checkout must take the same rows in the same order,
     * or the two deadlock. The add is replayed here statement by statement, with a pause in the
     * middle to widen the window, and fails the way Spring Data does when a row changed under it.
     * Guarantee: never a deadlock, and the add either lands (in this order or the next cart) or
     * fails with a concurrency error that the controller retries — it is never silently lost.
     */
    @Test
    void addingToTheCartDuringCheckoutNeverDeadlocksOrLosesTheItem() throws Exception {
        // No deadlock retry here, so a deadlock would surface instead of being retried away.
        com.laioffer.onlineorder.ordering.CheckoutService noRetry = new com.laioffer.onlineorder.ordering.CheckoutService(
                jdbc, new com.laioffer.onlineorder.platform.Tx(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource), 1),
                orders, inventory, 900);
        for (int round = 0; round < 8; round++) {
            long c = customer("dl" + round + "@test");
            addToCart(c, 1, 1);
            List<Callable<String>> both = List.of(
                    () -> {
                        new org.springframework.transaction.support.TransactionTemplate(
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource))
                                .executeWithoutResult(s -> {
                                    java.util.Map<String, Object> cart = jdbc.queryForMap("SELECT id, version FROM carts WHERE customer_id = ?", c);
                                    java.util.List<Long> item = jdbc.queryForList("SELECT id FROM order_items WHERE cart_id = ?", Long.class, cart.get("id"));
                                    if (item.isEmpty() || jdbc.update("UPDATE order_items SET quantity = quantity + 1 WHERE id = ?", item.get(0)) == 0) {
                                        throw new org.springframework.dao.IncorrectUpdateSemanticsDataAccessException("item row is gone");
                                    }
                                    InventoryConcurrencyIT.sleep(150);
                                    if (jdbc.update("UPDATE carts SET version = version + 1 WHERE id = ? AND version = ?", cart.get("id"), cart.get("version")) == 0) {
                                        throw new org.springframework.dao.OptimisticLockingFailureException("cart changed");
                                    }
                                });
                        return "added";
                    },
                    () -> noRetry.checkout(c, null, null).status().name());
            List<Object> r = race(both);
            assertTrue(r.stream().noneMatch(x -> x instanceof org.springframework.dao.PessimisticLockingFailureException),
                    "round " + round + " deadlocked: " + r);
            assertEquals("PLACED", r.get(1), "round " + round + ": " + r);
            int ordered = count("SELECT COALESCE(SUM(l.quantity), 0) FROM order_lines l JOIN orders o ON o.id = l.order_id WHERE o.customer_id = ?", c);
            int inCart = count("SELECT COALESCE(SUM(quantity), 0) FROM order_items oi JOIN carts ct ON ct.id = oi.cart_id WHERE ct.customer_id = ?", c);
            if ("added".equals(r.get(0))) {
                assertEquals(2, ordered + inCart, "round " + round + ": the added unit is in the order");
            } else {
                assertTrue(r.get(0) instanceof org.springframework.dao.ConcurrencyFailureException
                        || r.get(0) instanceof org.springframework.dao.IncorrectUpdateSemanticsDataAccessException,
                        "round " + round + ": the add failed loudly (the controller retries it): " + r.get(0));
                assertEquals(1, ordered + inCart, "round " + round);
            }
        }
    }

    @Test
    void emptyAndMixedCartsAreRejected() {
        long c = customer("m@test");
        assertEquals("EMPTY_CART", assertThrows(ApiException.class, () -> checkout.checkout(c, null, null)).code());
        addToCart(c, 1, 1);    // restaurant 1
        addToCart(c, 6, 1);    // restaurant 2
        assertEquals("MIXED_RESTAURANTS", assertThrows(ApiException.class, () -> checkout.checkout(c, null, null)).code());
    }

    /** NOTIFY is transactional: a rolled-back checkout announces nothing, a committed one announces once. */
    @Test
    void liveUpdatesAreSentOnlyForCommittedChanges() throws Exception {
        try (Connection listen = dataSource.getConnection()) {
            listen.setAutoCommit(true);
            try (Statement st = listen.createStatement()) {
                st.execute("LISTEN order_updates");
            }
            PGConnection pg = listen.unwrap(PGConnection.class);

            setStock(2, 0);
            long c = customer("n@test");
            addToCart(c, 2, 1);
            assertThrows(OutOfStock.class, () -> checkout.checkout(c, null, null));
            PGNotification[] none = pg.getNotifications(300);
            assertTrue(none == null || none.length == 0, "rolled back: nothing announced");

            setStock(2, 1);
            OrderView o = checkout.checkout(c, null, null);
            PGNotification[] n = pg.getNotifications(2000);
            assertEquals(1, n.length);
            assertTrue(n[0].getParameter().contains("\"orderId\":" + o.id()), n[0].getParameter());
        }
    }
}
