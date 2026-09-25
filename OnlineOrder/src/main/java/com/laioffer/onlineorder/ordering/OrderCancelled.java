package com.laioffer.onlineorder.ordering;

/**
 * Published synchronously, inside the cancelling transaction. Lets the payment module refund
 * without the ordering module depending on it (payment depends on ordering, not the reverse).
 */
public record OrderCancelled(long orderId, OrderStatus previousStatus, String reason) {
}
