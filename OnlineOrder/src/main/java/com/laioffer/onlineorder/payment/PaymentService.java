package com.laioffer.onlineorder.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laioffer.onlineorder.ordering.OrderCancelled;
import com.laioffer.onlineorder.ordering.OrderStatus;
import com.laioffer.onlineorder.ordering.Orders;
import com.laioffer.onlineorder.platform.ApiException;
import com.laioffer.onlineorder.platform.BusinessMetrics;
import com.laioffer.onlineorder.platform.Outbox;
import com.laioffer.onlineorder.platform.Tx;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payments: start a charge, apply the provider's webhooks, refund.
 *
 * <p>Three rules keep money correct:
 * <ul>
 *   <li>The provider is never called inside a database transaction (a slow provider would hold
 *       row locks and a pooled connection). Charges start after commit; refunds go through the
 *       outbox.</li>
 *   <li>Webhooks are de-duplicated by the provider's event id in the same transaction that
 *       applies them, so a redelivered event changes nothing.</li>
 *   <li>Every captured or refunded cent is posted to the double-entry {@link Ledger}.</li>
 * </ul>
 */
@Service
public class PaymentService {

    public record PaymentView(long orderId, String status, long amountCents, String paymentRef) {
    }

    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Orders orders;
    private final Ledger ledger;
    private final Outbox outbox;
    private final PaymentProvider provider;
    private final WebhookSignature signature;
    private final ObjectMapper json;

    public PaymentService(JdbcTemplate jdbc, Tx tx, Orders orders, Ledger ledger, Outbox outbox,
                          PaymentProvider provider, WebhookSignature signature, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.ledger = ledger;
        this.outbox = outbox;
        this.provider = provider;
        this.signature = signature;
        this.json = json;
    }

    /** Creates (or re-uses) the order's payment and asks the provider to charge it. */
    public PaymentView startPayment(long orderId, long customerId) {
        PaymentView p = tx.run(() -> {
            Orders.Order o = orders.lock(orderId);
            if (o.customerId() != customerId) {
                throw ApiException.notFound("order " + orderId);
            }
            if (o.status() != OrderStatus.PLACED) {
                throw ApiException.conflict("NOT_PAYABLE", "order " + orderId + " is " + o.status());
            }
            // One payment per order (UNIQUE order_id). A failed one can be retried; the retry is a
            // new charge at the provider, so it gets a new reference (late events for the old
            // reference then match nothing and are ignored).
            jdbc.update("""
                    INSERT INTO payments (order_id, amount_cents, status, provider_ref)
                    VALUES (?, ?, 'PENDING', ?)
                    ON CONFLICT (order_id) DO UPDATE
                    SET status = 'PENDING', provider_ref = EXCLUDED.provider_ref, updated_at = now()
                    WHERE payments.status = 'FAILED'
                    """, orderId, o.totalCents(), "pay_" + UUID.randomUUID());
            return jdbc.queryForObject(
                    "SELECT order_id, status, amount_cents, provider_ref FROM payments WHERE order_id = ?",
                    (rs, i) -> new PaymentView(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)),
                    orderId);
        });
        if ("PENDING".equals(p.status())) {
            provider.charge(p.paymentRef(), p.amountCents()); // after commit, never inside the transaction
        }
        return p;
    }

    /**
     * Applies one provider event. Returns what happened: processed, duplicate, ignored, refunded
     * or rejected. Throws 401 for a bad signature.
     */
    public String handleWebhook(String body, String signatureHeader) {
        String outcome;
        try {
            outcome = applyWebhook(body, signatureHeader);
        } catch (ApiException e) {
            BusinessMetrics.count("payments.webhooks", "outcome", e.code().toLowerCase());
            throw e;
        }
        BusinessMetrics.count("payments.webhooks", "outcome", outcome);
        return outcome;
    }

    private String applyWebhook(String body, String signatureHeader) {
        if (!signature.verify(body, signatureHeader)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "webhook signature is invalid or expired");
        }
        JsonNode e;
        try {
            e = json.readTree(body);
        } catch (IOException ex) {
            throw ApiException.badRequest("BAD_WEBHOOK", "unreadable webhook body");
        }
        String eventId = e.path("id").asText();
        String type = e.path("type").asText();
        String ref = e.path("payment_ref").asText();
        long amount = e.path("amount_cents").asLong();

        return tx.run(() -> {
            List<String> fresh = jdbc.queryForList("""
                    INSERT INTO payment_events (provider_event_id, type) VALUES (?, ?)
                    ON CONFLICT (provider_event_id) DO NOTHING
                    RETURNING provider_event_id
                    """, String.class, eventId, type);
            if (fresh.isEmpty()) {
                return "duplicate";
            }
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, order_id, amount_cents, status FROM payments WHERE provider_ref = ? FOR UPDATE", ref);
            if (rows.isEmpty()) {
                return "ignored";
            }
            Map<String, Object> payment = rows.get(0);
            long paymentId = ((Number) payment.get("id")).longValue();
            long orderId = ((Number) payment.get("order_id")).longValue();
            jdbc.update("UPDATE payment_events SET payment_id = ? WHERE provider_event_id = ?", paymentId, eventId);
            if (!"PENDING".equals(payment.get("status"))) {
                return "ignored";
            }

            switch (type) {
                case "payment.succeeded" -> {
                    if (amount != ((Number) payment.get("amount_cents")).longValue()) {
                        // The provider took money, but not the amount we asked for. Don't accept
                        // it as payment; record what moved and give it all back.
                        jdbc.update("UPDATE payments SET status = 'FAILED', updated_at = now() WHERE id = ?", paymentId);
                        ledger.post(orderId, "mismatched capture", Map.of(
                                Ledger.PROCESSOR_RECEIVABLE, amount, Ledger.CUSTOMER_REFUNDS_PAYABLE, -amount));
                        ledger.post(orderId, "refund of mismatched capture", Map.of(
                                Ledger.PROCESSOR_RECEIVABLE, -amount, Ledger.CUSTOMER_REFUNDS_PAYABLE, amount));
                        outbox.append(RefundHandler.TOPIC, Map.of(
                                "order_id", orderId, "payment_ref", ref, "amount_cents", amount,
                                "reason", "captured amount did not match the order"));
                        return "refunded";
                    }
                    jdbc.update("UPDATE payments SET status = 'CAPTURED', updated_at = now() WHERE id = ?", paymentId);
                    Orders.Order o = orders.lock(orderId);
                    ledger.postCapture(orderId, o.restaurantId(), amount);
                    if (o.status() == OrderStatus.PLACED) {
                        orders.transition(o, OrderStatus.PLACED, OrderStatus.PAID, "payment", null);
                        return "processed";
                    }
                    // The order expired (or was cancelled) while the customer was paying: give the money back.
                    refund(orderId, "payment arrived after the order was " + o.status().name().toLowerCase());
                    return "refunded";
                }
                case "payment.failed" -> {
                    jdbc.update("UPDATE payments SET status = 'FAILED', updated_at = now() WHERE id = ?", paymentId);
                    return "processed";
                }
                default -> {
                    return "ignored";
                }
            }
        });
    }

    /** A cancelled order that was paid gets its money back, in the cancelling transaction. */
    @EventListener
    public void onOrderCancelled(OrderCancelled e) {
        if (e.previousStatus().isPaid()) {
            refund(e.orderId(), e.reason());
        }
    }

    /**
     * Refunds the order's captured payment, if any: marks it refunded, reverses the ledger
     * posting and queues the provider refund in the outbox. Call inside a transaction.
     */
    public boolean refund(long orderId, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status, amount_cents, provider_ref FROM payments WHERE order_id = ? FOR UPDATE", orderId);
        if (rows.isEmpty() || !"CAPTURED".equals(rows.get(0).get("status"))) {
            return false;
        }
        Map<String, Object> p = rows.get(0);
        jdbc.update("UPDATE payments SET status = 'REFUNDED', updated_at = now() WHERE id = ?", p.get("id"));
        ledger.postRefund(orderId);
        outbox.append(RefundHandler.TOPIC, Map.of(
                "order_id", orderId,
                "payment_ref", p.get("provider_ref"),
                "amount_cents", p.get("amount_cents"),
                "reason", reason));
        return true;
    }
}
