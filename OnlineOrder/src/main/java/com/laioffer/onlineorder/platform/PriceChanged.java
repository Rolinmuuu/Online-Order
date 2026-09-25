package com.laioffer.onlineorder.platform;

import org.springframework.http.HttpStatus;

/** The cart costs something else now than what the customer was shown. Carries the new total. */
public class PriceChanged extends ApiException {

    private final long totalCents;

    public PriceChanged(long totalCents, long expectedCents) {
        super(HttpStatus.CONFLICT, "PRICE_CHANGED",
                "prices changed: the total is now " + totalCents + " cents, not " + expectedCents);
        this.totalCents = totalCents;
    }

    public long totalCents() {
        return totalCents;
    }
}
