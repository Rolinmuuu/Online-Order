package com.laioffer.onlineorder.payment;

import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PaymentController {

    private final PaymentService payments;
    private final CustomerService customers;

    public PaymentController(PaymentService payments, CustomerService customers) {
        this.payments = payments;
        this.customers = customers;
    }

    /** Body of POST /orders/{id}/pay: the processor's token for the card (optional). */
    public record PayBody(String paymentMethod) {
    }

    @PostMapping("/orders/{id}/pay")
    public PaymentService.PaymentView pay(@AuthenticationPrincipal User user, @PathVariable("id") long orderId,
                                          @RequestBody(required = false) PayBody body) {
        return payments.startPayment(orderId, customers.getCustomerByEmail(user.getUsername()).id(),
                body == null ? null : body.paymentMethod());
    }

    /** Called by the payment provider; authenticated by the HMAC signature, not by a session. */
    @PostMapping("/payments/webhook")
    public Map<String, String> webhook(@RequestBody String body,
                                       @RequestHeader(value = "X-Payment-Signature", required = false) String signature) {
        return Map.of("outcome", payments.handleWebhook(body, signature));
    }
}
