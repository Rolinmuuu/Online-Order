package com.laioffer.onlineorder.ordering;

import com.laioffer.onlineorder.platform.OrderUpdatesHub;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
public class OrderController {

    /** Body of POST /orders. The client sends the total it displayed, to catch price changes. */
    public record CheckoutBody(Long expectedTotalCents) {
    }

    private final CheckoutService checkout;
    private final OrderService orders;
    private final CustomerService customers;
    private final OrderUpdatesHub hub;

    public OrderController(CheckoutService checkout, OrderService orders, CustomerService customers, OrderUpdatesHub hub) {
        this.checkout = checkout;
        this.orders = orders;
        this.customers = customers;
        this.hub = hub;
    }

    private long customerId(User user) {
        return customers.getCustomerByEmail(user.getUsername()).id();
    }

    /** Checkout: turns the cart into an order. Send a fresh Idempotency-Key per attempt and reuse it on retry. */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView checkout(@AuthenticationPrincipal User user,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                              @RequestBody(required = false) CheckoutBody body) {
        return checkout.checkout(customerId(user), key, body == null ? null : body.expectedTotalCents());
    }

    @GetMapping("/orders")
    public List<OrderView> list(@AuthenticationPrincipal User user) {
        return orders.listForCustomer(customerId(user));
    }

    @GetMapping("/orders/{id}")
    public OrderView get(@AuthenticationPrincipal User user, @PathVariable("id") long id) {
        return orders.get(id, customerId(user));
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@AuthenticationPrincipal User user, @PathVariable("id") long id) {
        return orders.cancelByCustomer(id, customerId(user));
    }

    /** Server-Sent Events: a message whenever one of the signed-in customer's orders changes. */
    @GetMapping(value = "/orders/stream", produces = "text/event-stream")
    public SseEmitter stream(@AuthenticationPrincipal User user) {
        long me = customerId(user);
        return hub.subscribe(u -> u.customerId() == me);
    }
}
