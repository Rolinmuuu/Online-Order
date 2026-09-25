package com.laioffer.onlineorder.ordering;

/** Lets benchmarks in other packages flip CheckoutService's package-private switch. */
public final class CheckoutTestAccess {
    private CheckoutTestAccess() {
    }

    public static void reserveStockFirst(CheckoutService s, boolean first) {
        s.reserveStockFirst = first;
    }
}
