package com.laioffer.onlineorder.platform;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {

    private final MeterRegistry metrics;

    public ApiErrors(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        // By business code: http.server.requests only says "409", this says OUT_OF_STOCK vs PRICE_CHANGED.
        metrics.counter("api.errors", "code", e.code()).increment();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        if (e instanceof OutOfStock o) {
            body.put("menu_item_id", o.menuItemId());
            body.put("available", o.available());
        }
        if (e instanceof PriceChanged p) {
            body.put("total_cents", p.totalCents());
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    /** Lost a race that retrying inside the request could not resolve: the client may retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, Object>> concurrency(ConcurrencyFailureException e) {
        metrics.counter("api.errors", "code", "CONCURRENT_UPDATE").increment();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONCURRENT_UPDATE", "message", "the resource changed, please retry"));
    }
}
