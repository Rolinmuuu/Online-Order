package com.laioffer.onlineorder.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.laioffer.onlineorder.platform.OutboxTopicHandler;
import org.springframework.stereotype.Component;

/**
 * Sends queued refunds to the provider. The outbox id is the provider's idempotency key, so a
 * redelivered message (dispatcher crashed after the call, before marking the row) refunds once.
 */
@Component
public class RefundHandler implements OutboxTopicHandler {

    public static final String TOPIC = "payment.refund";

    private final PaymentProvider provider;

    public RefundHandler(PaymentProvider provider) {
        this.provider = provider;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    /** Calls the provider, so it must not run inside a database transaction. */
    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(long outboxId, JsonNode payload) {
        provider.refund(payload.get("payment_ref").asText(), payload.get("amount_cents").asLong(),
                "refund-" + outboxId);
    }
}
