package com.laioffer.onlineorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * docs/openapi.json is the committed API contract. This test fails when the running API no
 * longer matches it, so an API change is always a visible diff in review, never a surprise to
 * the frontend. After an intended change: UPDATE_OPENAPI=true ./gradlew test --tests '*OpenApiContract*'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.background-jobs.enabled=false", "spring.cache.type=simple", "management.server.port=0"})
class OpenApiContractTests {

    static final Path CONTRACT = Path.of("../docs/openapi.json");

    @LocalManagementPort
    int managementPort;

    final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void theApiMatchesTheCommittedContract() throws Exception {
        String body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/openapi")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        ObjectNode actual = (ObjectNode) json.readTree(body);
        actual.remove("servers"); // contains the random test port

        if ("true".equals(System.getenv("UPDATE_OPENAPI")) || !Files.exists(CONTRACT)) {
            Files.writeString(CONTRACT, json.writeValueAsString(actual) + "\n");
        }
        JsonNode committed = json.readTree(Files.readString(CONTRACT));
        assertEquals(committed, actual, "the API changed: review it, then regenerate docs/openapi.json with UPDATE_OPENAPI=true");
    }
}
