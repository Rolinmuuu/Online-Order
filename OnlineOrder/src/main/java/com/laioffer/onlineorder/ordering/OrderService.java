package com.laioffer.onlineorder.ordering;

import com.laioffer.onlineorder.inventory.InventoryService;
import com.laioffer.onlineorder.platform.ApiException;
import com.laioffer.onlineorder.platform.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Reading orders and moving them through their lifecycle (customer, kitchen and system actions). */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public enum KitchenAction { ACCEPT, READY, COMPLETE, REJECT }

    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final Orders orders;
    private final InventoryService inventory;
    private final ApplicationEventPublisher events;

    public OrderService(JdbcTemplate jdbc, Tx tx, Orders orders, InventoryService inventory,
                        ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.inventory = inventory;
        this.events = events;
    }

    public OrderView get(long orderId, long customerId) {
        OrderView v = orders.view(orderId);
        if (v.customerId() != customerId) {
            throw ApiException.notFound("order " + orderId); // don't reveal other customers' orders
        }
        return v;
    }

    public List<OrderView> listForCustomer(long customerId) {
        return orders.idsForCustomer(customerId, 20).stream().map(orders::view).toList();
    }

    /** A customer may cancel until the restaurant accepts; a paid order is refunded. */
    public OrderView cancelByCustomer(long orderId, long customerId) {
        tx.runVoid(() -> {
            Orders.Order o = orders.find(orderId);
            if (o.customerId() != customerId) {
                throw ApiException.notFound("order " + orderId);
            }
            if (o.status() != OrderStatus.PLACED && o.status() != OrderStatus.PAID) {
                throw ApiException.conflict("TOO_LATE_TO_CANCEL", o.status() == OrderStatus.CANCELLED
                        ? "this order is already cancelled"
                        : "the restaurant is already working on this order");
            }
            cancel(o, "customer", "cancelled by the customer");
        });
        return orders.view(orderId);
    }

    // ───────────── kitchen ─────────────

    public List<Map<String, Object>> restaurantsFor(String staffEmail) {
        return jdbc.queryForList("""
                SELECT r.id, r.name, r.image_url FROM restaurants r
                JOIN restaurant_staff s ON s.restaurant_id = r.id
                WHERE s.email = ? ORDER BY r.id
                """, staffEmail);
    }

    public void requireStaff(String staffEmail, long restaurantId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM restaurant_staff WHERE email = ? AND restaurant_id = ?",
                Integer.class, staffEmail, restaurantId);
        if (n == null || n == 0) {
            throw ApiException.forbidden("you are not staff of restaurant " + restaurantId);
        }
    }

    public List<OrderView> kitchenBoard(String staffEmail, long restaurantId) {
        requireStaff(staffEmail, restaurantId);
        return orders.activeIdsForRestaurant(restaurantId).stream().map(orders::view).toList();
    }

    public OrderView kitchenAction(String staffEmail, long orderId, KitchenAction action) {
        tx.runVoid(() -> {
            Orders.Order o = orders.find(orderId);
            requireStaff(staffEmail, o.restaurantId());
            String actor = "kitchen:" + staffEmail;
            switch (action) {
                case ACCEPT -> orders.transition(o, OrderStatus.PAID, OrderStatus.ACCEPTED, actor, null);
                case READY -> orders.transition(o, OrderStatus.ACCEPTED, OrderStatus.READY, actor, null);
                case COMPLETE -> orders.transition(o, OrderStatus.READY, OrderStatus.COMPLETED, actor, null);
                case REJECT -> {
                    if (o.status() != OrderStatus.PAID && o.status() != OrderStatus.ACCEPTED) {
                        throw ApiException.conflict("INVALID_TRANSITION", "cannot reject an order that is " + o.status());
                    }
                    cancel(o, actor, "the restaurant could not prepare this order");
                }
            }
        });
        return orders.view(orderId);
    }

    // ───────────── system ─────────────

    /**
     * Cancels unpaid orders whose payment window has passed and returns their stock.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several instances sweep at once: each claims
     * different rows and none waits on another. An order being paid at this very moment is
     * either locked by the webhook (skipped here, picked up next pass if still unpaid) or already
     * PAID (no longer matches). The partial index on (pay_by) WHERE status = 'PLACED' keeps this
     * query cheap however many completed orders the table holds.
     */
    public int expireOverdue(int limit) {
        return tx.run(() -> {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM orders
                    WHERE status = 'PLACED' AND pay_by < now()
                    ORDER BY pay_by
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, Long.class, limit);
            for (long id : ids) {
                cancel(orders.find(id), "system", "not paid in time");
            }
            return ids.size();
        });
    }

    @Scheduled(fixedDelayString = "${orders.expiry-sweep-ms:5000}")
    public void sweep() {
        try {
            int n;
            do {
                n = expireOverdue(100);
                if (n > 0) {
                    log.info("expired {} unpaid orders", n);
                }
            } while (n == 100);
        } catch (RuntimeException e) {
            log.warn("expiry sweep failed", e);
        }
    }

    /** Cancel, return the stock, and let the payment module refund. Call inside a transaction. */
    private void cancel(Orders.Order o, String actor, String reason) {
        OrderStatus from = o.status();
        orders.transition(o, from, OrderStatus.CANCELLED, actor, reason);
        inventory.release(orders.itemsOf(o.id()));
        events.publishEvent(new OrderCancelled(o.id(), from, reason));
    }
}
