package com.laioffer.onlineorder.ordering;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle. The allowed moves are the only ones {@link Orders#transition} will perform:
 *
 * <pre>
 *   PLACED ──pay──▶ PAID ──accept──▶ ACCEPTED ──ready──▶ READY ──pick up──▶ COMPLETED
 *     │               │                  │
 *     └──cancel/expire┴────reject────────┴──────────▶ CANCELLED   (stock released; refund if paid)
 * </pre>
 */
public enum OrderStatus {
    PLACED, PAID, ACCEPTED, READY, COMPLETED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PLACED, EnumSet.of(PAID, CANCELLED),
            PAID, EnumSet.of(ACCEPTED, CANCELLED),
            ACCEPTED, EnumSet.of(READY, CANCELLED),
            READY, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canMoveTo(OrderStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** Money has been captured for orders in these states, so cancelling them means a refund. */
    public boolean isPaid() {
        return this == PAID || this == ACCEPTED || this == READY || this == COMPLETED;
    }
}
