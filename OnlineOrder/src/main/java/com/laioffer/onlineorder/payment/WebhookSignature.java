package com.laioffer.onlineorder.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Stripe-style webhook signatures: header {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 *
 * <p>The HMAC proves the sender knows the shared secret and the body was not altered; the
 * timestamp (checked within a 5-minute window) stops an old, captured request from being
 * replayed later. Comparison is constant-time.
 */
public final class WebhookSignature {

    static final long TOLERANCE_SECONDS = 300;

    private final byte[] secret;
    private final Clock clock;

    public WebhookSignature(String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(String body) {
        long t = clock.instant().getEpochSecond();
        return "t=" + t + ",v1=" + hmac(t + "." + body);
    }

    public boolean verify(String body, String header) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - t) > TOLERANCE_SECONDS) {
            return false;
        }
        return MessageDigest.isEqual(
                hmac(t + "." + body).getBytes(StandardCharsets.US_ASCII),
                v1.getBytes(StandardCharsets.US_ASCII));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
