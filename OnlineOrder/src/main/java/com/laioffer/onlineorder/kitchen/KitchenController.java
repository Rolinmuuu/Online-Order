package com.laioffer.onlineorder.kitchen;

import com.laioffer.onlineorder.ordering.OrderService;
import com.laioffer.onlineorder.ordering.OrderView;
import com.laioffer.onlineorder.payment.Ledger;
import com.laioffer.onlineorder.platform.ApiException;
import com.laioffer.onlineorder.platform.OrderUpdatesHub;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The restaurant side: a live board of paid orders and the actions that move them along.
 * Lives outside {@code ordering} because it also reads the {@code payment} ledger; keeping it
 * here keeps the module dependencies one-way (payment → ordering → inventory).
 */
@RestController
public class KitchenController {

    private final OrderService orders;
    private final OrderUpdatesHub hub;
    private final Ledger ledger;

    public KitchenController(OrderService orders, OrderUpdatesHub hub, Ledger ledger) {
        this.orders = orders;
        this.hub = hub;
        this.ledger = ledger;
    }

    @GetMapping("/kitchen/restaurants")
    public List<Map<String, Object>> myRestaurants(@AuthenticationPrincipal User user) {
        return orders.restaurantsFor(user.getUsername());
    }

    @GetMapping("/kitchen/restaurants/{rid}/orders")
    public Map<String, Object> board(@AuthenticationPrincipal User user, @PathVariable("rid") long restaurantId) {
        List<OrderView> board = orders.kitchenBoard(user.getUsername(), restaurantId);
        // What the platform owes this restaurant, straight from the ledger (credit balance).
        long owed = -ledger.balance(Ledger.restaurantPayable(restaurantId));
        return Map.of("orders", board, "payable_cents", owed);
    }

    @PostMapping("/kitchen/orders/{id}/{action}")
    public OrderView act(@AuthenticationPrincipal User user, @PathVariable("id") long orderId,
                         @PathVariable("action") String action) {
        OrderService.KitchenAction a;
        try {
            a = OrderService.KitchenAction.valueOf(action.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNKNOWN_ACTION", "unknown action " + action);
        }
        return orders.kitchenAction(user.getUsername(), orderId, a);
    }

    @GetMapping(value = "/kitchen/restaurants/{rid}/stream", produces = "text/event-stream")
    public SseEmitter stream(@AuthenticationPrincipal User user, @PathVariable("rid") long restaurantId) {
        orders.requireStaff(user.getUsername(), restaurantId);
        return hub.subscribe(u -> u.restaurantId() == restaurantId);
    }
}
