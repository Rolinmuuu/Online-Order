package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.ordering.NotificationHandler;
import com.laioffer.onlineorder.payment.Ledger;
import com.laioffer.onlineorder.platform.Outbox;
import com.laioffer.onlineorder.platform.OutboxDispatcher;
import com.laioffer.onlineorder.platform.OutboxHandlers;
import com.laioffer.onlineorder.platform.OutboxTopicHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerAndOutboxIT extends PostgresTestSupport {

    // ───────── ledger ─────────

    @Test
    void theDatabaseRejectsAnUnbalancedTransactionAtCommit() {
        UUID txn = UUID.randomUUID();
        RuntimeException e = assertThrows(RuntimeException.class, () -> tx.runVoid(() -> {
            jdbc.update("INSERT INTO ledger_entries (txn_id, account, amount_cents, memo) VALUES (?, 'a', 100, 't')", txn);
            jdbc.update("INSERT INTO ledger_entries (txn_id, account, amount_cents, memo) VALUES (?, 'b', -99, 't')", txn);
        }));
        assertTrue(rootMessage(e).contains("does not balance"), rootMessage(e));
        assertEquals(0, count("SELECT count(*) FROM ledger_entries"));

        // Legs may be inserted one by one: the check runs at COMMIT, not per row.
        tx.runVoid(() -> {
            jdbc.update("INSERT INTO ledger_entries (txn_id, account, amount_cents, memo) VALUES (?, 'a', 100, 't')", txn);
            jdbc.update("INSERT INTO ledger_entries (txn_id, account, amount_cents, memo) VALUES (?, 'b', -100, 't')", txn);
        });
        assertEquals(2, count("SELECT count(*) FROM ledger_entries"));
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    @Test
    void ledgerEntriesCannotBeEditedOrDeleted() {
        long id = paidOrder(customer("l@test"), 1, 1);
        assertEquals(3, count("SELECT count(*) FROM ledger_entries WHERE order_id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE ledger_entries SET amount_cents = 6"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM ledger_entries"));
    }

    @Test
    void commissionRoundingStillBalances() {
        long c = customer("odd@test");
        addToCart(c, 4, 1);    // 475 cents: 15% = 71.25 → 71
        long id = paidOrder(c, 5, 1) ; // separate order, 600 cents
        assertTrue(id > 0);
        assertEquals(0, count("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries"));
        assertEquals(71, Ledger.commission(475));
    }

    // ───────── outbox ─────────

    @Test
    void aRolledBackTransactionLeavesNoMessage() {
        Outbox outbox = new Outbox(jdbc, json);
        assertThrows(IllegalStateException.class, () -> tx.runVoid(() -> {
            outbox.append(NotificationHandler.TOPIC, Map.of("customer_id", 1, "order_id", 1, "message", "x"));
            throw new IllegalStateException("business rule failed");
        }));
        assertEquals(0, count("SELECT count(*) FROM outbox"));
    }

    @Test
    void threeDispatchersDeliverEachMessageExactlyOnce() throws Exception {
        Outbox outbox = new Outbox(jdbc, json);
        for (int i = 0; i < 300; i++) {
            int n = i;
            tx.runVoid(() -> outbox.append(NotificationHandler.TOPIC,
                    Map.of("customer_id", 1, "order_id", n, "message", "m" + n)));
        }
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            workers.add(() -> {
                int total = 0;
                int got;
                while ((got = dispatcher.runOnce(20)) > 0) {
                    total += got;
                }
                return total;
            });
        }
        List<Object> claimed = race(workers);
        assertEquals(300, claimed.stream().mapToInt(x -> (Integer) x).sum(), claimed.toString());
        assertEquals(300, count("SELECT count(*) FROM notifications"));
        assertEquals(0, count("SELECT count(*) FROM outbox WHERE processed_at IS NULL"));
    }

    @Test
    void aFailingHandlerIsRetriedLaterWithoutBlockingOthers() {
        Outbox outbox = new Outbox(jdbc, json);
        OutboxTopicHandler flaky = new OutboxTopicHandler() {
            @Override
            public String topic() {
                return "flaky";
            }

            @Override
            public void handle(long outboxId, JsonNode payload) {
                jdbc.update("INSERT INTO notifications (outbox_id, customer_id, order_id, message) VALUES (?, 1, 1, 'partial')", outboxId);
                throw new IllegalStateException("provider timed out");
            }
        };
        OutboxDispatcher d = new OutboxDispatcher(jdbc, tx, json,
                new OutboxHandlers(List.of(flaky, new NotificationHandler(jdbc))));
        tx.runVoid(() -> {
            outbox.append("flaky", Map.of());
            outbox.append(NotificationHandler.TOPIC, Map.of("customer_id", 1, "order_id", 2, "message", "ok"));
        });

        assertEquals(2, d.runOnce(10));

        Map<String, Object> failed = jdbc.queryForMap("SELECT attempts, last_error, available_at > now() AS later FROM outbox WHERE topic = 'flaky'");
        assertEquals(1, failed.get("attempts"));
        assertNotNull(failed.get("last_error"));
        assertEquals(true, failed.get("later"));
        // The failing handler's partial write was rolled back with its transaction; the other message went out.
        assertEquals(List.of("ok"), jdbc.queryForList("SELECT message FROM notifications", String.class));
    }
}
