package com.laioffer.onlineorder.platform;

import org.springframework.http.HttpStatus;

public class OutOfStock extends ApiException {

    private final long menuItemId;
    private final int available;

    public OutOfStock(long menuItemId, String name, int available) {
        super(HttpStatus.CONFLICT, "OUT_OF_STOCK",
                available == 0 ? name + " is sold out for today" : "only " + available + " left of " + name);
        this.menuItemId = menuItemId;
        this.available = available;
    }

    public long menuItemId() {
        return menuItemId;
    }

    public int available() {
        return available;
    }
}
