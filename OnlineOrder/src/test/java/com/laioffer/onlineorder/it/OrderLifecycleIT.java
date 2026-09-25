package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.ordering.OrderService.KitchenAction;
import com.laioffer.onlineorder.ordering.OrderStatus;
import com.laioffer.onlineorder.ordering.OrderView;
import com.laioffer.onlineorder.payment.Ledger;
import com.laioffer.onlineorder.payment.PaymentService;
import com.laioffer.onlineorder.payment.WebhookSignature;
import com.laioffer.onlineorder.platform.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderLifecycleIT extends PostgresTestSupport {

    static final String KITCHEN = "kitchen@test";

    @Test
    void happyPathFromCheckoutToPickUp() {
        staff(KITCHEN, 1);
        long c = customer("a@test");
        addToCart(c, 1, 2);   // 2 × 12.50
        long id = checkout.checkout(c, "k", 2500L).id();

        PaymentService.PaymentView p = payments.startPayment(id, c);
        assertEquals(List.of(p.paymentRef()), provider.charges);
        assertEquals("processed", payments.handleWebhook(succeeded(p), signature.sign(succeeded(p))));
        orderService.kitchenAction(KITCHEN, id, KitchenAction.ACCEPT);
        orderService.kitchenAction(KITCHEN, id, KitchenAction.READY);
        OrderView done = orderService.kitchenAction(KITCHEN, id, KitchenAction.COMPLETE);

        assertEquals(OrderStatus.COMPLETED, done.status());
        assertEquals(List.of("PLACED", "PAID", "ACCEPTED", "READY", "COMPLETED"),
                done.events().stream().map(OrderView.Event::toStatus).toList());
        assertEquals("CAPTURED", done.paymentStatus());

        // Ledger: 2500 captured, 15% commission (375) to the platform, 2125 owed to the restaurant.
        assertEquals(2500, ledger.balance(Ledger.PROCESSOR_RECEIVABLE));
        assertEquals(-2125, ledger.balance(Ledger.restaurantPayable(1)));
        assertEquals(-375, ledger.balance(Ledger.PLATFORM_REVENUE));

        dispatcher.runOnce(100);
        assertEquals(5, count("SELECT count(*) FROM notifications WHERE order_id = ?", id));
    }

    @Test
    void aWebhookDeliveredFiveTimesAtOnceIsAppliedOnce() throws Exception {
        long c = customer("w@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();
        PaymentService.PaymentView p = payments.startPayment(id, c);
        String body = succeeded(p);
        List<Callable<String>> deliveries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            deliveries.add(() -> payments.handleWebhook(body, signature.sign(body)));
        }
        List<Object> outcomes = race(deliveries);
        assertEquals(1, outcomes.stream().filter("processed"::equals).count(), outcomes.toString());
        assertEquals(4, outcomes.stream().filter("duplicate"::equals).count(), outcomes.toString());
        assertEquals(1, count("SELECT count(DISTINCT txn_id) FROM ledger_entries WHERE order_id = ?", id));
        assertEquals(OrderStatus.PAID, orders.find(id).status());
    }

    @Test
    void forgedOrReplayedWebhooksAreRejected() {
        long c = customer("f@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();
        PaymentService.PaymentView p = payments.startPayment(id, c);
        String body = succeeded(p);

        WebhookSignature attacker = new WebhookSignature("guessed-secret", Clock.systemUTC());
        assertEquals(401, assertThrows(ApiException.class,
                () -> payments.handleWebhook(body, attacker.sign(body))).status().value());

        WebhookSignature tenMinutesAgo = new WebhookSignature(SECRET,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-10)));
        assertEquals(401, assertThrows(ApiException.class,
                () -> payments.handleWebhook(body, tenMinutesAgo.sign(body))).status().value());

        String tampered = body.replace("\"amount_cents\":1250", "\"amount_cents\":1");
        assertEquals(401, assertThrows(ApiException.class,
                () -> payments.handleWebhook(tampered, signature.sign(body))).status().value());
        assertEquals(OrderStatus.PLACED, orders.find(id).status());
    }

    /** The kitchen accepts while the customer cancels: exactly one wins, and the state is consistent. */
    @Test
    void acceptAndCancelRaceHasExactlyOneWinner() throws Exception {
        staff(KITCHEN, 1);
        setStock(2, 100);
        for (int round = 0; round < 15; round++) {
            long c = customer("r" + round + "@test");
            long id = paidOrder(c, 2, 1);
            List<Object> r = race(List.<Callable<OrderView>>of(
                    () -> orderService.kitchenAction(KITCHEN, id, KitchenAction.ACCEPT),
                    () -> orderService.cancelByCustomer(id, c)));
            long wins = r.stream().filter(x -> x instanceof OrderView).count();
            assertEquals(1, wins, "round " + round + ": " + r);
            OrderStatus s = orders.find(id).status();
            if (s == OrderStatus.CANCELLED) {
                assertEquals("REFUNDED", orders.view(id).paymentStatus());
            } else {
                assertEquals(OrderStatus.ACCEPTED, s);
                assertEquals("CAPTURED", orders.view(id).paymentStatus());
            }
        }
        // Every cancelled order gave its unit back; every accepted one kept it.
        int accepted = count("SELECT count(*) FROM orders WHERE status = 'ACCEPTED'");
        assertEquals(100 - accepted, stock(2));
    }

    @Test
    void aRejectedOrderIsRefundedAndItsStockReturned() {
        staff(KITCHEN, 1);
        setStock(2, 5);
        long c = customer("x@test");
        long id = paidOrder(c, 2, 2);
        assertEquals(3, stock(2));

        OrderView v = orderService.kitchenAction(KITCHEN, id, KitchenAction.REJECT);

        assertEquals(OrderStatus.CANCELLED, v.status());
        assertEquals("REFUNDED", v.paymentStatus());
        assertEquals(5, stock(2));
        assertEquals(0, count("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries WHERE order_id = ?", id));
        assertEquals(0, ledger.balance(Ledger.restaurantPayable(1)));

        // The provider refund goes through the outbox; delivering it twice refunds once.
        dispatcher.runOnce(100);
        jdbc.update("UPDATE outbox SET processed_at = NULL, available_at = now() WHERE topic = 'payment.refund'"); // simulate a redelivery
        dispatcher.runOnce(100);
        assertEquals(2, provider.refundCalls.get());
        assertEquals(1, provider.refunds.size(), "idempotency key refund-<outbox id> dedupes at the provider");
    }

    @Test
    void twoSweepersExpireEachUnpaidOrderOnce() throws Exception {
        setStock(2, 20);
        for (int i = 0; i < 12; i++) {
            long c = customer("u" + i + "@test");
            addToCart(c, 2, 1);
            checkout.checkout(c, null, null);
        }
        assertEquals(8, stock(2));
        jdbc.update("UPDATE orders SET pay_by = now() - interval '1 minute'");

        List<Object> swept = race(List.<Callable<Integer>>of(
                () -> orderService.expireOverdue(100), () -> orderService.expireOverdue(100)));

        assertEquals(12, ((Integer) swept.get(0)) + ((Integer) swept.get(1)), swept.toString());
        assertEquals(12, count("SELECT count(*) FROM order_events WHERE to_status = 'CANCELLED'"));
        assertEquals(20, stock(2), "each unit returned exactly once");
    }

    @Test
    void aPaymentThatLandsAfterExpiryIsRefunded() {
        long c = customer("late@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();
        PaymentService.PaymentView p = payments.startPayment(id, c);
        jdbc.update("UPDATE orders SET pay_by = now() - interval '1 second' WHERE id = ?", id);
        orderService.expireOverdue(10);

        assertEquals("refunded", payments.handleWebhook(succeeded(p), signature.sign(succeeded(p))));
        assertEquals(OrderStatus.CANCELLED, orders.find(id).status());
        assertEquals("REFUNDED", orders.view(id).paymentStatus());
        assertEquals(0, count("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries WHERE order_id = ?", id));
    }

    @Test
    void aFailedPaymentCanBeRetriedAsANewCharge() {
        long c = customer("retrypay@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();
        PaymentService.PaymentView first = payments.startPayment(id, c);
        String failed = "{\"id\":\"evt_fail\",\"type\":\"payment.failed\",\"payment_ref\":\"" + first.paymentRef()
                + "\",\"amount_cents\":1250,\"failure_reason\":\"insufficient_funds\"}";
        assertEquals("processed", payments.handleWebhook(failed, signature.sign(failed)));
        assertEquals("FAILED", orders.view(id).paymentStatus());
        assertEquals("insufficient_funds", orders.view(id).paymentFailureReason(), "the customer is told why");
        assertEquals(OrderStatus.PLACED, orders.find(id).status(), "still payable with another card");

        PaymentService.PaymentView retry = payments.startPayment(id, c);
        assertEquals("PENDING", retry.status());
        assertEquals(null, orders.view(id).paymentFailureReason(), "a new attempt clears the old reason");
        assertTrue(!retry.paymentRef().equals(first.paymentRef()), "a retry is a new charge with a new reference");
        assertEquals(List.of(first.paymentRef(), retry.paymentRef()), provider.charges);
        assertEquals("processed", payments.handleWebhook(succeeded(retry), signature.sign(succeeded(retry))));
        assertEquals(OrderStatus.PAID, orders.find(id).status());
        // A late success for the abandoned first charge matches nothing.
        assertEquals("ignored", payments.handleWebhook(succeeded(first), signature.sign(succeeded(first))));
    }

    @Test
    void aCaptureForTheWrongAmountIsGivenBack() {
        long c = customer("amount@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();
        PaymentService.PaymentView p = payments.startPayment(id, c);
        String wrong = "{\"id\":\"evt_wrong\",\"type\":\"payment.succeeded\",\"payment_ref\":\"" + p.paymentRef()
                + "\",\"amount_cents\":999}";

        assertEquals("refunded", payments.handleWebhook(wrong, signature.sign(wrong)));

        assertEquals(OrderStatus.PLACED, orders.find(id).status(), "not accepted as payment");
        assertEquals(4, count("SELECT count(*) FROM ledger_entries WHERE order_id = ?", id), "movement recorded");
        assertEquals(0, count("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries WHERE order_id = ?", id));
        dispatcher.runOnce(10);
        assertEquals(1, provider.refunds.size(), "the 999 cents go back to the customer");
    }

    @Test
    void illegalMovesAndOutsidersAreRejected() {
        staff(KITCHEN, 1);
        long c = customer("i@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();

        ApiException notPaid = assertThrows(ApiException.class,
                () -> orderService.kitchenAction(KITCHEN, id, KitchenAction.ACCEPT));
        assertEquals(409, notPaid.status().value());

        ApiException stranger = assertThrows(ApiException.class,
                () -> orderService.kitchenAction("someone@test", id, KitchenAction.REJECT));
        assertEquals(403, stranger.status().value());

        long other = customer("other@test");
        assertEquals(404, assertThrows(ApiException.class, () -> orderService.get(id, other)).status().value());
        assertTrue(orders.find(id).status() == OrderStatus.PLACED);
    }

    /** A declined card changes no order status, but the waiting order page must still hear about it. */
    @Test
    void aDeclinedPaymentIsAnnouncedToLiveScreens() throws Exception {
        long c = customer("declined@test");
        addToCart(c, 1, 1);
        long id = checkout.checkout(c, null, null).id();
        PaymentService.PaymentView p = payments.startPayment(id, c, "tok_chargeDeclined");
        try (java.sql.Connection listen = dataSource.getConnection()) {
            listen.setAutoCommit(true);
            try (java.sql.Statement st = listen.createStatement()) {
                st.execute("LISTEN order_updates");
            }
            String failed = "{\"id\":\"evt_decl\",\"type\":\"payment.failed\",\"payment_ref\":\"" + p.paymentRef()
                    + "\",\"amount_cents\":1250,\"failure_reason\":\"card_declined\"}";
            payments.handleWebhook(failed, signature.sign(failed));

            org.postgresql.PGNotification[] n = listen.unwrap(org.postgresql.PGConnection.class).getNotifications(2000);
            assertEquals(1, n.length);
            assertTrue(n[0].getParameter().contains("\"orderId\":" + id), n[0].getParameter());
        }
    }
}
