package com.laioffer.onlineorder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Metrics are reachable on the internal management port only; every response carries a trace id. */
@AutoConfigureObservability // Spring Boot tests switch metrics export and tracing off unless asked
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.background-jobs.enabled=false", "spring.cache.type=simple", "management.server.port=0"})
class ActuatorExposureTests {

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(int p, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + p + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndPrometheusAnswerOnTheManagementPort() throws Exception {
        assertEquals(200, get(managementPort, "/actuator/health/readiness").statusCode());
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus");
        assertEquals(200, prometheus.statusCode());
        assertTrue(prometheus.body().contains("outbox_pending"));
    }

    @Test
    void thePublicPortDoesNotServeMetrics() throws Exception {
        assertNotEquals(200, get(port, "/actuator/prometheus").statusCode());
        assertNotEquals(200, get(port, "/actuator/metrics").statusCode());
    }

    @Test
    void responsesCarryATraceIdEvenWhenRejected() throws Exception {
        HttpResponse<String> unauthorized = get(port, "/orders");
        assertEquals(401, unauthorized.statusCode());
        assertTrue(unauthorized.headers().firstValue("X-Trace-Id").orElse("").matches("[0-9a-f]{32}"));
    }
}
