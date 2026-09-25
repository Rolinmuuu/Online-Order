package com.laioffer.onlineorder.ordering;

import java.time.Instant;
import java.util.List;

public record OrderView(
        long id,
        OrderStatus status,
        long customerId,
        long restaurantId,
        String restaurantName,
        long totalCents,
        Instant payBy,
        Instant createdAt,
        String cancelReason,
        String paymentStatus,
        List<Line> lines,
        List<Event> events
) {
    public record Line(long menuItemId, String name, long unitPriceCents, int quantity) {
    }

    public record Event(String fromStatus, String toStatus, String actor, String reason, Instant at) {
    }
}
