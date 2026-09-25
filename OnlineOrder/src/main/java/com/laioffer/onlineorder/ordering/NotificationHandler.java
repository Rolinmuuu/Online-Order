package com.laioffer.onlineorder.ordering;

import com.fasterxml.jackson.databind.JsonNode;
import com.laioffer.onlineorder.platform.OutboxTopicHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivers customer notifications. Here "delivery" is a row in {@code notifications} (standing in
 * for e-mail or push); UNIQUE (outbox_id) makes a redelivered message a no-op.
 */
@Component
public class NotificationHandler implements OutboxTopicHandler {

    public static final String TOPIC = "order.notification";

    private final JdbcTemplate jdbc;

    public NotificationHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public void handle(long outboxId, JsonNode payload) {
        jdbc.update("""
                INSERT INTO notifications (outbox_id, customer_id, order_id, message)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (outbox_id) DO NOTHING
                """,
                outboxId,
                payload.get("customer_id").asLong(),
                payload.get("order_id").asLong(),
                payload.get("message").asText());
    }
}
