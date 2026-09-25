package com.laioffer.onlineorder.controller;

import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.model.AddToCartBody;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.service.CartService;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CartController {


    private final CartService cartService;
    private final CustomerService customerService;


    public CartController(
            CartService cartService,
            CustomerService customerService
    ) {
        this.cartService = cartService;
        this.customerService = customerService;
    }


    @GetMapping("/cart")
    public CartDto getCart(@AuthenticationPrincipal User user) {
        CustomerEntity customer = customerService.getCustomerByEmail(user.getUsername());
        return cartService.getCart(customer.id());
    }


    @PostMapping("/cart")
    public void addToCart(@AuthenticationPrincipal User user, @RequestBody AddToCartBody body) {
        CustomerEntity customer = customerService.getCustomerByEmail(user.getUsername());
        // Two quick taps race on the cart: @Version makes the loser fail instead of silently
        // overwriting the total, and it simply runs again on the fresh row.
        for (int attempt = 1; ; attempt++) {
            try {
                cartService.addMenuItemToCart(customer.id(), body.menuId());
                return;
            } catch (RuntimeException e) {
                if (attempt >= 3 || !isConcurrentUpdate(e)) {
                    throw e;
                }
            }
        }
    }


    private static boolean isConcurrentUpdate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // IncorrectUpdateSemantics: the item row was deleted meanwhile (a checkout emptied the
            // cart); running again adds the item to the now-empty cart.
            if (t instanceof OptimisticLockingFailureException || t instanceof DuplicateKeyException
                    || t instanceof IncorrectUpdateSemanticsDataAccessException) {
                return true;
            }
        }
        return false;
    }


    // Empties the cart. (Checkout, which creates an order, is POST /orders.)
    @PostMapping({"/cart/clear", "/cart/checkout"})
    public void checkout(@AuthenticationPrincipal User user) {
        CustomerEntity customer = customerService.getCustomerByEmail(user.getUsername());
        cartService.clearCart(customer.id());
    }
}
