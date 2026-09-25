package com.laioffer.onlineorder;

import com.laioffer.onlineorder.ordering.CheckoutService;
import com.laioffer.onlineorder.ordering.OrderService;
import com.laioffer.onlineorder.ordering.OrderStatus;
import com.laioffer.onlineorder.ordering.OrderView;
import com.laioffer.onlineorder.payment.PaymentService;
import com.laioffer.onlineorder.payment.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Background loops off: this context must not compete with other tests for the database.
@SpringBootTest(properties = {"app.background-jobs.enabled=false", "spring.cache.type=simple"})
class OnlineOrderApplicationTests {

    @Autowired
    CheckoutService checkout;
    @Autowired
    PaymentService payments;
    @Autowired
    OrderService orders;
    @Autowired
    WebhookSignature signature;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoads() {
    }

    /**
     * The real Spring wiring of a refund: rejecting a paid order publishes OrderCancelled, and
     * PaymentService's @EventListener refunds inside the same transaction.
     */
    @Test
    void rejectingAPaidOrderRefundsItThroughTheSpringEventListener() {
        long customer = jdbc.queryForObject(
                "INSERT INTO customers (email, password, enabled) VALUES ('wiring@test', 'x', true) RETURNING id", Long.class);
        jdbc.update("INSERT INTO carts (customer_id, total_price) VALUES (?, 0)", customer);
        jdbc.update("INSERT INTO order_items (menu_item_id, cart_id, price, quantity) SELECT 1, id, 12.50, 1 FROM carts WHERE customer_id = ?", customer);
        jdbc.update("INSERT INTO customers (email, password, enabled) VALUES ('staff@test', 'x', true)");
        jdbc.update("INSERT INTO restaurant_staff (email, restaurant_id) VALUES ('staff@test', 1)");

        long orderId = checkout.checkout(customer, "wiring-key", 1250L).id();
        PaymentService.PaymentView p = payments.startPayment(orderId, customer);
        String event = "{\"id\":\"evt_wiring\",\"type\":\"payment.succeeded\",\"payment_ref\":\"" + p.paymentRef()
                + "\",\"amount_cents\":1250}";
        assertEquals("processed", payments.handleWebhook(event, signature.sign(event)));

        OrderView rejected = orders.kitchenAction("staff@test", orderId, OrderService.KitchenAction.REJECT);

        assertEquals(OrderStatus.CANCELLED, rejected.status());
        assertEquals("REFUNDED", rejected.paymentStatus());
    }
}
