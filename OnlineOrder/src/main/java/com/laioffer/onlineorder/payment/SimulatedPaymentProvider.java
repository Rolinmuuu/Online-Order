package com.laioffer.onlineorder.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stand-in for a card processor so the whole flow runs locally without real payments. Only
 * present in demo mode ({@code app.demo=true}); with demo mode off the application refuses to
 * start until a real {@link PaymentProvider} is provided.
 *
 * <p>It behaves the way real processors do in the ways that matter for correctness: the result
 * of a charge arrives asynchronously as a signed webhook, and each event is delivered
 * <b>twice</b> (at-least-once delivery), so the webhook handler's de-duplication is exercised on
 * every payment, not only in tests.
 *
 * <p>Like a real processor in test mode, the outcome follows the test card's token:
 * {@code tok_visa} is approved, {@code tok_chargeDeclined} and
 * {@code tok_chargeDeclinedInsufficientFunds} are declined, any other token is rejected as
 * invalid.
 */
@Component
@ConditionalOnProperty(name = "app.demo", havingValue = "true", matchIfMissing = true)
public class SimulatedPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

    public record Refund(String paymentRef, long amountCents, String idempotencyKey) {
    }

    private final ObjectProvider<PaymentService> receiver;
    private final WebhookSignature signature;
    private final ObjectMapper json;
    private final long delayMs;
    private final Set<String> charged = ConcurrentHashMap.newKeySet();
    private final Map<String, Refund> refunds = new ConcurrentHashMap<>();
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "simulated-payment-provider");
        t.setDaemon(true);
        return t;
    });

    public SimulatedPaymentProvider(ObjectProvider<PaymentService> receiver, WebhookSignature signature,
                                    ObjectMapper json, @Value("${payments.simulated.delay-ms:1200}") long delayMs) {
        this.receiver = receiver;
        this.signature = signature;
        this.json = json;
        this.delayMs = delayMs;
    }

    static final Map<String, String> DECLINES = Map.of(
            "tok_chargeDeclined", "card_declined",
            "tok_chargeDeclinedInsufficientFunds", "insufficient_funds");

    @Override
    public void charge(String paymentRef, long amountCents, String paymentMethod) {
        if (!charged.add(paymentRef)) {
            return; // already charging this payment
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + UUID.randomUUID());
        event.put("payment_ref", paymentRef);
        event.put("amount_cents", amountCents);
        if (PaymentProvider.DEFAULT_PAYMENT_METHOD.equals(paymentMethod)) {
            event.put("type", "payment.succeeded");
        } else {
            event.put("type", "payment.failed");
            event.put("failure_reason", DECLINES.getOrDefault(paymentMethod, "invalid_payment_method"));
        }
        String body;
        try {
            body = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        // Deliver the same event twice, like a provider whose first acknowledgement got lost.
        scheduler.schedule(() -> deliver(body), delayMs, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> deliver(body), delayMs * 2, TimeUnit.MILLISECONDS);
    }

    private void deliver(String body) {
        try {
            String outcome = receiver.getObject().handleWebhook(body, signature.sign(body));
            delivered.add(outcome);
        } catch (RuntimeException e) {
            log.warn("simulated webhook delivery failed", e);
        }
    }

    @Override
    public void refund(String paymentRef, long amountCents, String idempotencyKey) {
        refunds.putIfAbsent(idempotencyKey, new Refund(paymentRef, amountCents, idempotencyKey));
        log.info("simulated refund of {} cents for {}", amountCents, paymentRef);
    }

    public Map<String, Refund> refunds() {
        return refunds;
    }

    public List<String> deliveryOutcomes() {
        return delivered;
    }
}
