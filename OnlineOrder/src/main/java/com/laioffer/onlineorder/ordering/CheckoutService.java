package com.laioffer.onlineorder.ordering;

import com.laioffer.onlineorder.inventory.InventoryService;
import com.laioffer.onlineorder.platform.ApiException;
import com.laioffer.onlineorder.platform.PriceChanged;
import com.laioffer.onlineorder.platform.Tx;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Turns a cart into an order in one ACID transaction.
 *
 * <pre>
 *  1. claim the Idempotency-Key      INSERT ... ON CONFLICT DO NOTHING (a concurrent duplicate waits here)
 *  2. lock the cart's item rows, then the cart row   (same order as add-to-cart takes them: no deadlock)
 *  3. price the cart                 prices and names copied from the menu, in integer cents
 *  4. write order, lines, audit event, outbox notification, NOTIFY
 *  5. empty the cart
 *  6. reserve limited stock          last, so the hot inventory rows are locked for the shortest time
 *  7. link the key to the order      commit: all of it becomes visible at once, or none of it
 * </pre>
 *
 * Any failure (out of stock, price changed, empty cart) rolls the whole transaction back,
 * including the key claim, so the client may retry with the same key.
 */
@Service
public class CheckoutService {

    private record CartLine(long menuItemId, String name, long restaurantId, long unitPriceCents, int quantity) {
    }

    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Orders orders;
    private final InventoryService inventory;
    private final long paySeconds;
    /** Benchmark switch only (docs/SCALING.md): take the stock at the start instead of last. */
    boolean reserveStockFirst = false;

    public CheckoutService(JdbcTemplate jdbc, Tx tx, Orders orders, InventoryService inventory,
                           @Value("${orders.pay-within-seconds:900}") long paySeconds) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.inventory = inventory;
        this.paySeconds = paySeconds;
    }

    /**
     * @param idempotencyKey   client-generated key per checkout attempt; retries reuse it (may be null)
     * @param expectedTotalCents the total the client displayed; if prices changed since, 409 (may be null)
     * @return the new order, or the order this key already produced
     */
    public OrderView checkout(long customerId, String idempotencyKey, Long expectedTotalCents) {
        long orderId = tx.run(() -> {
            if (idempotencyKey != null) {
                Long existing = claimKey(customerId, idempotencyKey, expectedTotalCents);
                if (existing != null) {
                    return existing;
                }
            }

            // Lock order: item rows first, then the cart row. Add-to-cart updates an item row and
            // then the cart row; taking them in the same order means the two wait for each other
            // instead of deadlocking. Holding the cart row also keeps a concurrent add-to-cart
            // from committing between reading the cart and emptying it (its cart update waits).
            Long cartId = jdbc.queryForObject("SELECT id FROM carts WHERE customer_id = ?", Long.class, customerId);
            List<Long> itemRowIds = jdbc.queryForList(
                    "SELECT id FROM order_items WHERE cart_id = ? ORDER BY id FOR UPDATE", Long.class, cartId);
            jdbc.queryForList("SELECT id FROM carts WHERE id = ? FOR UPDATE", Long.class, cartId);
            List<CartLine> cart = jdbc.query("""
                    SELECT oi.menu_item_id, mi.name, mi.restaurant_id,
                           ROUND(mi.price * 100)::bigint AS unit_price_cents, oi.quantity
                    FROM order_items oi
                    JOIN menu_items mi ON mi.id = oi.menu_item_id
                    WHERE oi.id = ANY (?)
                    ORDER BY oi.menu_item_id
                    """, (rs, i) -> new CartLine(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5)),
                    (Object) itemRowIds.toArray(new Long[0]));
            if (cart.isEmpty()) {
                throw ApiException.badRequest("EMPTY_CART", "your cart is empty");
            }
            long restaurantId = cart.get(0).restaurantId();
            if (cart.stream().anyMatch(l -> l.restaurantId() != restaurantId)) {
                throw ApiException.conflict("MIXED_RESTAURANTS", "an order can only contain items from one restaurant");
            }
            long total = cart.stream().mapToLong(l -> l.unitPriceCents() * l.quantity()).sum();
            if (expectedTotalCents != null && expectedTotalCents != total) {
                throw new PriceChanged(total, expectedTotalCents);
            }

            List<InventoryService.Item> items = cart.stream()
                    .map(l -> new InventoryService.Item(l.menuItemId(), l.name(), l.quantity()))
                    .toList();
            if (reserveStockFirst) {
                inventory.reserve(items);
            }

            long id = orders.insert(customerId, restaurantId, total, paySeconds);
            for (CartLine l : cart) {
                orders.insertLine(id, l.menuItemId(), l.name(), l.unitPriceCents(), l.quantity());
            }
            orders.created(orders.find(id), "customer");

            jdbc.update("DELETE FROM order_items WHERE id = ANY (?)", (Object) itemRowIds.toArray(new Long[0]));
            jdbc.update("UPDATE carts SET total_price = 0, version = version + 1 WHERE id = ?", cartId);

            if (!reserveStockFirst) {
                inventory.reserve(items); // last: the contended rows stay locked only until commit
            }

            if (idempotencyKey != null) {
                jdbc.update("UPDATE idempotency_keys SET order_id = ? WHERE customer_id = ? AND key = ?",
                        id, customerId, idempotencyKey);
            }
            return id;
        });
        return orders.view(orderId);
    }

    /**
     * Claims the key for this transaction and returns null, or returns the order an earlier,
     * committed request with the same key produced.
     *
     * <p>If another transaction inserted the same key and has not committed yet, PostgreSQL makes
     * this INSERT wait for it: if it commits, we see the conflict and replay its order; if it
     * rolls back, our insert succeeds and we run the checkout ourselves. The database, not the
     * application, decides who wins, so there is no "in progress" state to expose.
     */
    private Long claimKey(long customerId, String key, Long expectedTotalCents) {
        if (key.length() > 128) {
            throw ApiException.badRequest("BAD_IDEMPOTENCY_KEY", "Idempotency-Key is too long");
        }
        String requestHash = expectedTotalCents == null ? "-" : Long.toString(expectedTotalCents);
        List<String> claimed = jdbc.queryForList("""
                INSERT INTO idempotency_keys (customer_id, key, request_hash) VALUES (?, ?, ?)
                ON CONFLICT (customer_id, key) DO NOTHING
                RETURNING key
                """, String.class, customerId, key, requestHash);
        if (!claimed.isEmpty()) {
            return null;
        }
        Map<String, Object> prior = jdbc.queryForMap(
                "SELECT request_hash, order_id FROM idempotency_keys WHERE customer_id = ? AND key = ?",
                customerId, key);
        if (!requestHash.equals(prior.get("request_hash"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "this Idempotency-Key was used for a different request");
        }
        return ((Number) prior.get("order_id")).longValue();
    }
}
