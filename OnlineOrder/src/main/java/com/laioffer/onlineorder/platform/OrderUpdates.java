package com.laioffer.onlineorder.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces an order change to every application instance through PostgreSQL NOTIFY.
 *
 * <p>NOTIFY is transactional: the message is delivered only if the transaction that sent it
 * commits, and only after it commits. So a browser can never be told about an order state that
 * was rolled back, and no message broker is needed for live updates. Payloads are kept small
 * (ids and the new status); clients re-read details over HTTP.
 */
@Component
public class OrderUpdates {

    public static final String CHANNEL = "order_updates";

    public record Update(long orderId, long customerId, long restaurantId, String status) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrderUpdates(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Must be called inside the transaction that made the change. */
    public void publish(Update update) {
        try {
            jdbc.queryForList("SELECT pg_notify(?, ?)", CHANNEL, json.writeValueAsString(update));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
