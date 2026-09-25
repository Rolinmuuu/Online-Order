package com.laioffer.onlineorder.payment;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PaymentConfig {

    static final String DEV_SECRET = "dev-only-webhook-secret";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    WebhookSignature webhookSignature(@Value("${payments.webhook-secret:" + DEV_SECRET + "}") String secret,
                                      @Value("${app.demo:true}") boolean demo, Clock clock) {
        if (DEV_SECRET.equals(secret)) {
            if (!demo) {
                // Anyone who knows the public development secret could forge "payment succeeded".
                throw new IllegalStateException("set PAYMENT_WEBHOOK_SECRET: the development secret is only allowed in demo mode");
            }
            LoggerFactory.getLogger(PaymentConfig.class)
                    .warn("payments.webhook-secret is not set; using the development secret (demo mode)");
        }
        return new WebhookSignature(secret, clock);
    }
}
