package com.laioffer.onlineorder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signup validation, duplicate accounts and rate limiting, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.background-jobs.enabled=false", "spring.cache.type=simple", "management.server.port=0",
        "rate-limit.login-per-minute=3", "rate-limit.signup-per-minute=100"})
class WebHardeningTests {

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> signup(String email, String password) throws Exception {
        return post("/signup", "application/json",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"first_name\":\"A\",\"last_name\":\"B\"}");
    }

    @Test
    void signupRejectsInvalidInputWithFieldErrors() throws Exception {
        HttpResponse<String> r = signup("not-an-email", "short");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("VALIDATION_FAILED"));
        assertTrue(r.body().contains("\"email\""));
        assertTrue(r.body().contains("\"password\""));
    }

    @Test
    void aSecondSignupWithTheSameEmailIsAConflict() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@test.com";
        assertEquals(201, signup(email, "long-enough-password").statusCode());
        HttpResponse<String> again = signup(email.toUpperCase(), "long-enough-password");
        assertEquals(409, again.statusCode());
        assertTrue(again.body().contains("EMAIL_TAKEN"));
    }

    @Test
    void repeatedLoginAttemptsAreRateLimited() throws Exception {
        String form = "username=nobody-" + UUID.randomUUID() + "@test.com&password=wrong";
        for (int i = 0; i < 3; i++) {
            assertEquals(401, post("/login", "application/x-www-form-urlencoded", form).statusCode());
        }
        HttpResponse<String> limited = post("/login", "application/x-www-form-urlencoded", form);
        assertEquals(429, limited.statusCode());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        assertTrue(limited.body().contains("RATE_LIMITED"));
    }
}
