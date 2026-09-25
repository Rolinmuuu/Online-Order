package com.laioffer.onlineorder.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Records a side effect in the same transaction as the state change that causes it.
 *
 * <p>If the transaction rolls back, the message disappears with it; if it commits, the message
 * is guaranteed to be delivered (at least once) by {@link OutboxDispatcher}. This removes the
 * "saved the order but never sent the confirmation" and "sent a confirmation for an order that
 * was rolled back" failure modes of calling another system inside or after a transaction.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Must be called inside a transaction (see {@link Tx}). */
    public long append(String topic, Map<String, ?> payload) {
        try {
            return jdbc.queryForObject(
                    "INSERT INTO outbox (topic, payload) VALUES (?, ?::jsonb) RETURNING id",
                    Long.class, topic, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("outbox payload is not serialisable", e);
        }
    }
}
