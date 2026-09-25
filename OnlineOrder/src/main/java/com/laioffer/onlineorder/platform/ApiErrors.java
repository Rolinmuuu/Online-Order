package com.laioffer.onlineorder.platform;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
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
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONCURRENT_UPDATE", "message", "the resource changed, please retry"));
    }
}
