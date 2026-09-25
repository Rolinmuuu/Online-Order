package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.ordering.OrderView;
import com.laioffer.onlineorder.platform.OutOfStock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Limited stock under contention: never oversold, never deadlocked. */
class InventoryConcurrencyIT extends PostgresTestSupport {

    static final long TRUFFLE_BURGER = 2;   // limited item in the seed data
    static final long DOUBLE_SMASH = 1;
    static final long FRIES = 4;

    @Test
    void fortyCustomersRaceForTenUnitsAndExactlyTenWin() throws Exception {
        setStock(TRUFFLE_BURGER, 10);
        List<Callable<OrderView>> checkouts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long c = customer("c" + i + "@test");
            addToCart(c, TRUFFLE_BURGER, 1);
            checkouts.add(() -> checkout.checkout(c, null, null));
        }

        List<Object> results = race(checkouts);

        long won = results.stream().filter(r -> r instanceof OrderView).count();
        long soldOut = results.stream().filter(r -> r instanceof OutOfStock).count();
        assertEquals(10, won, "exactly the stock should sell: " + results);
        assertEquals(30, soldOut);
        assertEquals(0, stock(TRUFFLE_BURGER));
        assertEquals(10, count("SELECT COALESCE(SUM(quantity), 0) FROM order_lines WHERE menu_item_id = ?", TRUFFLE_BURGER));
        // The losers' transactions left nothing behind: no orders, and their carts are intact.
        assertEquals(10, count("SELECT count(*) FROM orders"));
        assertEquals(30, count("SELECT count(*) FROM order_items"));
    }

    @Test
    void aMultiUnitOrderIsAllOrNothing() {
        setStock(TRUFFLE_BURGER, 3);
        long c = customer("big@test");
        addToCart(c, TRUFFLE_BURGER, 5);
        addToCart(c, FRIES, 2);
        OutOfStock e = assertThrows(OutOfStock.class, () -> checkout.checkout(c, null, null));
        assertEquals(3, e.available());
        assertEquals(3, stock(TRUFFLE_BURGER), "nothing was taken");
        assertEquals(0, count("SELECT count(*) FROM orders"));
    }

    /**
     * The bug the conditional UPDATE prevents: read the stock, decide in the application, write
     * it back. Every transaction reads the same "5 left" and all of them sell.
     */
    @Test
    void readThenWriteInTheApplicationOversells() throws Exception {
        setStock(TRUFFLE_BURGER, 5);
        List<Callable<Boolean>> naive = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            naive.add(() -> tx.run(() -> {
                int left = jdbc.queryForObject("SELECT available FROM inventory WHERE menu_item_id = ?",
                        Integer.class, TRUFFLE_BURGER);
                if (left < 1) {
                    return false;
                }
                sleep(50); // the gap between check and act that concurrency slips into
                jdbc.update("UPDATE inventory SET available = ? WHERE menu_item_id = ?", left - 1, TRUFFLE_BURGER);
                return true;
            }));
        }
        long sold = race(naive).stream().filter(Boolean.TRUE::equals).count();
        assertTrue(sold > 5, "naive version should oversell, sold " + sold + " of 5");
    }

    /**
     * Two transactions lock the same two rows in opposite orders: PostgreSQL detects the cycle and
     * aborts one. Taking locks in ascending id order (what InventoryService does) removes it.
     */
    @Test
    void oppositeLockOrderDeadlocksAndSortedOrderDoesNot() throws Exception {
        setStock(DOUBLE_SMASH, 1000);
        setStock(TRUFFLE_BURGER, 1000);

        List<Callable<String>> unsorted = List.of(
                () -> lockBoth(DOUBLE_SMASH, TRUFFLE_BURGER),
                () -> lockBoth(TRUFFLE_BURGER, DOUBLE_SMASH));
        List<Object> r1 = race(unsorted);
        assertTrue(r1.stream().anyMatch(r -> r instanceof DeadlockLoserDataAccessException
                        || r instanceof PessimisticLockingFailureException),
                "opposite lock order should deadlock: " + r1);

        List<Callable<String>> sorted = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long c = customer("s" + i + "@test");
            // carts list the items in different orders; reserve() sorts them
            addToCart(c, i % 2 == 0 ? DOUBLE_SMASH : TRUFFLE_BURGER, 1);
            addToCart(c, i % 2 == 0 ? TRUFFLE_BURGER : DOUBLE_SMASH, 1);
            sorted.add(() -> checkout.checkout(c, null, null).status().name());
        }
        List<Object> r2 = race(sorted);
        assertTrue(r2.stream().allMatch("PLACED"::equals), "no deadlocks with sorted locking: " + r2);
    }

    private String lockBoth(long first, long second) {
        // Raw transaction without Tx's retry, so the deadlock surfaces.
        return new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)).execute(s -> {
            jdbc.update("UPDATE inventory SET available = available - 1 WHERE menu_item_id = ?", first);
            sleep(300);
            jdbc.update("UPDATE inventory SET available = available - 1 WHERE menu_item_id = ?", second);
            return "ok";
        });
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
