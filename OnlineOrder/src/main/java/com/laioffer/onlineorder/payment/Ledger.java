package com.laioffer.onlineorder.payment;

import com.laioffer.onlineorder.platform.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Double-entry ledger for money movements.
 *
 * <p>A posting is a set of legs that sums to zero (positive = debit, negative = credit). When a
 * customer pays an order of T cents:
 *
 * <pre>
 *   processor_receivable          +T          what the payment provider now owes us
 *   restaurant_payable:{id}       -(T - fee)  what we owe the restaurant
 *   platform_revenue              -fee        our commission (15%, rounded down)
 * </pre>
 *
 * A refund posts the same legs negated. Entries are never updated or deleted (a database trigger
 * rejects it), so every balance can be recomputed from history, and the database rejects any
 * transaction whose legs do not balance at COMMIT (a deferred constraint trigger) even if this
 * class had a bug.
 */
@Component
public class Ledger {

    public static final String PROCESSOR_RECEIVABLE = "processor_receivable";
    public static final String PLATFORM_REVENUE = "platform_revenue";
    /** Money the provider captured that belongs back to a customer (e.g. a wrong amount). */
    public static final String CUSTOMER_REFUNDS_PAYABLE = "customer_refunds_payable";
    static final int COMMISSION_PERCENT = 15;

    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String restaurantPayable(long restaurantId) {
        return "restaurant_payable:" + restaurantId;
    }

    public static long commission(long totalCents) {
        return totalCents * COMMISSION_PERCENT / 100;
    }

    /** Posts one balanced transaction. Call inside a database transaction. */
    public UUID post(long orderId, String memo, Map<String, Long> legs) {
        long sum = legs.values().stream().mapToLong(Long::longValue).sum();
        if (sum != 0) {
            throw new IllegalArgumentException("unbalanced posting (" + sum + " cents): " + legs);
        }
        UUID txn = UUID.randomUUID();
        legs.forEach((account, amount) -> {
            if (amount != 0) {
                jdbc.update("INSERT INTO ledger_entries (txn_id, account, amount_cents, order_id, memo) VALUES (?, ?, ?, ?, ?)",
                        txn, account, amount, orderId, memo);
            }
        });
        return txn;
    }

    public UUID postCapture(long orderId, long restaurantId, long totalCents) {
        long fee = commission(totalCents);
        Map<String, Long> legs = new LinkedHashMap<>();
        legs.put(PROCESSOR_RECEIVABLE, totalCents);
        legs.put(restaurantPayable(restaurantId), -(totalCents - fee));
        legs.put(PLATFORM_REVENUE, -fee);
        return post(orderId, "capture", legs);
    }

    /** Reverses the order's capture posting (refund). */
    public UUID postRefund(long orderId) {
        List<Map<String, Object>> capture = jdbc.queryForList(
                "SELECT account, amount_cents FROM ledger_entries WHERE order_id = ? AND memo = 'capture'", orderId);
        if (capture.isEmpty()) {
            throw ApiException.conflict("NOTHING_TO_REFUND", "order " + orderId + " has no captured payment");
        }
        Map<String, Long> legs = new LinkedHashMap<>();
        for (Map<String, Object> leg : capture) {
            legs.merge((String) leg.get("account"), -((Number) leg.get("amount_cents")).longValue(), Long::sum);
        }
        return post(orderId, "refund", legs);
    }

    public long balance(String account) {
        Long b = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries WHERE account = ?",
                Long.class, account);
        return b == null ? 0 : b;
    }
}
