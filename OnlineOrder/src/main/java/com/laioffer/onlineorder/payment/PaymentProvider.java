package com.laioffer.onlineorder.payment;

/** The card processor, as seen by this service. */
public interface PaymentProvider {

    /** The payment method used when the client names none (the simulator's always-approved card). */
    String DEFAULT_PAYMENT_METHOD = "tok_visa";

    /**
     * Starts a charge. The result arrives later as a signed webhook ({@code payment.succeeded} or
     * {@code payment.failed}). Must be idempotent on {@code paymentRef}: calling it twice (a
     * double-clicked Pay button) charges once.
     *
     * @param paymentMethod the processor's token for the card, created in the browser by the
     *                      processor's own form, so card numbers never reach this service
     */
    void charge(String paymentRef, long amountCents, String paymentMethod);

    /** Refunds a captured charge; idempotent on {@code idempotencyKey}. */
    void refund(String paymentRef, long amountCents, String idempotencyKey);
}
