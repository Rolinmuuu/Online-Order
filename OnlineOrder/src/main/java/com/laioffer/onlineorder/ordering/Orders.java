package com.laioffer.onlineorder.ordering;

import com.laioffer.onlineorder.inventory.InventoryService;
import com.laioffer.onlineorder.platform.ApiException;
import com.laioffer.onlineorder.platform.BusinessMetrics;
import com.laioffer.onlineorder.platform.Outbox;
import com.laioffer.onlineorder.platform.OrderUpdates;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Data access for orders, and the one place where an order changes state.
 *
 * <p>{@link #transition} is a guarded update: {@code UPDATE orders SET status = :to WHERE id = :id
 * AND status = :from}. If two actors race (the kitchen accepts while the customer cancels), both
 * read PAID, both try to move away from PAID, and exactly one UPDATE matches a row; the loser gets
 * a 409 and re-reads. Every successful transition writes an audit event, queues the customer
 * notification in the outbox and announces the change, all in the caller's transaction.
 */
@Component
public class Orders {

    public record Order(long id, long customerId, long restaurantId, OrderStatus status, long totalCents,
                        Instant payBy, Instant createdAt, String cancelReason) {
    }

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final OrderUpdates updates;

    public Orders(JdbcTemplate jdbc, Outbox outbox, OrderUpdates updates) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.updates = updates;
    }

    private static Order map(ResultSet rs, int i) throws SQLException {
        return new Order(rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("restaurant_id"),
                OrderStatus.valueOf(rs.getString("status")), rs.getLong("total_cents"),
                instant(rs.getTimestamp("pay_by")), instant(rs.getTimestamp("created_at")),
                rs.getString("cancel_reason"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final String COLUMNS =
            "id, customer_id, restaurant_id, status, total_cents, pay_by, created_at, cancel_reason";

    public Order find(long id) {
        List<Order> rows = jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE id = ?", Orders::map, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("order " + id);
        }
        return rows.get(0);
    }

    /** Reads the order and locks its row until the transaction ends. */
    public Order lock(long id) {
        List<Order> rows = jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE id = ? FOR UPDATE", Orders::map, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("order " + id);
        }
        return rows.get(0);
    }

    long insert(long customerId, long restaurantId, long totalCents, long paySeconds) {
        return jdbc.queryForObject("""
                INSERT INTO orders (customer_id, restaurant_id, status, total_cents, pay_by)
                VALUES (?, ?, 'PLACED', ?, now() + make_interval(secs => ?))
                RETURNING id
                """, Long.class, customerId, restaurantId, totalCents, paySeconds);
    }

    void insertLine(long orderId, long menuItemId, String name, long unitPriceCents, int quantity) {
        jdbc.update("""
                INSERT INTO order_lines (order_id, menu_item_id, name, unit_price_cents, quantity)
                VALUES (?, ?, ?, ?, ?)
                """, orderId, menuItemId, name, unitPriceCents, quantity);
    }

    /** Records creation the same way a transition is recorded. */
    void created(Order o, String actor) {
        jdbc.update("INSERT INTO order_events (order_id, from_status, to_status, actor) VALUES (?, NULL, 'PLACED', ?)",
                o.id(), actor);
        notifyCustomer(o, OrderStatus.PLACED, null);
        BusinessMetrics.countOnCommit("orders.transitions", "from", "NONE", "to", "PLACED", "actor", actor);
    }

    /**
     * Moves the order from {@code from} to {@code to} or throws 409 if it is no longer in
     * {@code from}. Call inside a transaction.
     */
    public Order transition(Order o, OrderStatus from, OrderStatus to, String actor, String reason) {
        if (!from.canMoveTo(to)) {
            throw ApiException.conflict("INVALID_TRANSITION", "an order cannot go from " + from + " to " + to);
        }
        int updated = jdbc.update("""
                UPDATE orders
                SET status = ?, version = version + 1, updated_at = now(),
                    cancel_reason = CASE WHEN ? = 'CANCELLED' THEN ? ELSE cancel_reason END
                WHERE id = ? AND status = ?
                """, to.name(), to.name(), reason, o.id(), from.name());
        if (updated == 0) {
            OrderStatus now = find(o.id()).status();
            throw ApiException.conflict("STATUS_CHANGED",
                    "order " + o.id() + " is " + now + " now, not " + from + "; refresh and try again");
        }
        jdbc.update("INSERT INTO order_events (order_id, from_status, to_status, actor, reason) VALUES (?, ?, ?, ?, ?)",
                o.id(), from.name(), to.name(), actor, reason);
        BusinessMetrics.countOnCommit("orders.transitions", "from", from.name(), "to", to.name(), "actor", actor);
        Order changed = new Order(o.id(), o.customerId(), o.restaurantId(), to, o.totalCents(), o.payBy(),
                o.createdAt(), to == OrderStatus.CANCELLED ? reason : o.cancelReason());
        notifyCustomer(changed, to, reason);
        return changed;
    }

    private void notifyCustomer(Order o, OrderStatus status, String reason) {
        outbox.append(NotificationHandler.TOPIC, Map.of(
                "customer_id", o.customerId(),
                "order_id", o.id(),
                "message", message(o, status, reason)));
        updates.publish(new OrderUpdates.Update(o.id(), o.customerId(), o.restaurantId(), status.name()));
    }

    private static String message(Order o, OrderStatus status, String reason) {
        return switch (status) {
            case PLACED -> "Order #" + o.id() + " placed. Please pay to confirm it.";
            case PAID -> "Payment received for order #" + o.id() + ".";
            case ACCEPTED -> "The restaurant is preparing order #" + o.id() + ".";
            case READY -> "Order #" + o.id() + " is ready for pick-up.";
            case COMPLETED -> "Order #" + o.id() + " picked up. Enjoy!";
            case CANCELLED -> "Order #" + o.id() + " was cancelled" + (reason == null ? "." : ": " + reason + ".");
        };
    }

    /**
     * Tells live screens to re-read the order although its status did not change (e.g. a declined
     * card on a still-unpaid order). Call inside the transaction that made the change.
     */
    public void announce(Order o) {
        updates.publish(new OrderUpdates.Update(o.id(), o.customerId(), o.restaurantId(), o.status().name()));
    }

    public List<InventoryService.Item> itemsOf(long orderId) {
        return jdbc.query("SELECT menu_item_id, name, quantity FROM order_lines WHERE order_id = ?",
                (rs, i) -> new InventoryService.Item(rs.getLong(1), rs.getString(2), rs.getInt(3)), orderId);
    }

    public OrderView view(long orderId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT o.id, o.customer_id, o.restaurant_id, r.name AS restaurant_name, o.status, o.total_cents,
                       o.pay_by, o.created_at, o.cancel_reason, p.status AS payment_status,
                       p.failure_reason AS payment_failure_reason
                FROM orders o
                JOIN restaurants r ON r.id = o.restaurant_id
                LEFT JOIN payments p ON p.order_id = o.id
                WHERE o.id = ?
                """, orderId);
        List<OrderView.Line> lines = jdbc.query("""
                SELECT menu_item_id, name, unit_price_cents, quantity FROM order_lines
                WHERE order_id = ? ORDER BY menu_item_id
                """, (rs, i) -> new OrderView.Line(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4)), orderId);
        List<OrderView.Event> events = jdbc.query("""
                SELECT from_status, to_status, actor, reason, created_at FROM order_events
                WHERE order_id = ? ORDER BY id
                """, (rs, i) -> new OrderView.Event(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), instant(rs.getTimestamp(5))), orderId);
        return new OrderView(
                ((Number) row.get("id")).longValue(),
                OrderStatus.valueOf((String) row.get("status")),
                ((Number) row.get("customer_id")).longValue(),
                ((Number) row.get("restaurant_id")).longValue(),
                (String) row.get("restaurant_name"),
                ((Number) row.get("total_cents")).longValue(),
                instant((Timestamp) row.get("pay_by")),
                instant((Timestamp) row.get("created_at")),
                (String) row.get("cancel_reason"),
                (String) row.get("payment_status"),
                (String) row.get("payment_failure_reason"),
                lines, events);
    }

    public List<Long> idsForCustomer(long customerId, int limit) {
        return jdbc.queryForList("SELECT id FROM orders WHERE customer_id = ? ORDER BY id DESC LIMIT ?",
                Long.class, customerId, limit);
    }

    public List<Long> activeIdsForRestaurant(long restaurantId) {
        return jdbc.queryForList("""
                SELECT id FROM orders
                WHERE restaurant_id = ?
                  AND (status IN ('PAID', 'ACCEPTED', 'READY')
                       OR (status IN ('COMPLETED', 'CANCELLED') AND updated_at > now() - interval '2 hours'
                           AND EXISTS (SELECT 1 FROM order_events e WHERE e.order_id = orders.id AND e.to_status = 'PAID')))
                ORDER BY id
                """, Long.class, restaurantId);
    }
}
