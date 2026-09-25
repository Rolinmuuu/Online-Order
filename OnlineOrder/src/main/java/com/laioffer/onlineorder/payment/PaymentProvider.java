package com.laioffer.onlineorder.payment;

/** The card processor, as seen by this service. */
public interface PaymentProvider {

    /**
     * Starts a charge. The result arrives later as a signed webhook. Must be idempotent on
     * {@code paymentRef}: calling it twice (a double-clicked Pay button) charges once.
     */
    void charge(String paymentRef, long amountCents);

    /** Refunds a captured charge; idempotent on {@code idempotencyKey}. */
    void refund(String paymentRef, long amountCents, String idempotencyKey);
}
