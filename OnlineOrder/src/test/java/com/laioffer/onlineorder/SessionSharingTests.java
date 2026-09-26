package com.laioffer.onlineorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two application instances on one database, like two tasks behind a load balancer. Sessions
 * live in PostgreSQL, so a customer signed in on one instance is signed in on the other, and
 * stays signed in when the instance that created the session goes away (a deploy).
 */
class SessionSharingTests {

    private final List<ConfigurableApplicationContext> instances = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stopAll() {
        instances.forEach(ConfigurableApplicationContext::close);
    }

    private int start() {
        ConfigurableApplicationContext app = new SpringApplicationBuilder(OnlineOrderApplication.class)
                // command-line arguments: they outrank application.yml (builder properties do not)
                .run("--server.port=0", "--management.server.port=0", "--app.background-jobs.enabled=false",
                        "--spring.cache.type=simple", "--rate-limit.enabled=false");
        instances.add(app);
        return ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI at(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void aSessionCreatedOnOneInstanceWorksOnAnotherAndOutlivesIt() throws Exception {
        int a = start();
        int b = start();
        String email = "session-" + UUID.randomUUID() + "@test.com";
        assertEquals(201, send(HttpRequest.newBuilder(at(a, "/signup")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"long-enough-password\"}"))).statusCode());

        HttpResponse<String> login = send(HttpRequest.newBuilder(at(a, "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + email + "&password=long-enough-password")));
        assertEquals(200, login.statusCode());
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        assertTrue(cookie.startsWith("SESSION="), cookie);

        HttpResponse<String> onB = send(HttpRequest.newBuilder(at(b, "/me")).header("Cookie", cookie));
        assertEquals(200, onB.statusCode(), "signed in on the other instance");
        assertTrue(onB.body().contains(email));

        instances.get(0).close(); // instance A is replaced by a deploy
        assertEquals(200, send(HttpRequest.newBuilder(at(b, "/me")).header("Cookie", cookie)).statusCode(),
                "still signed in after the instance that created the session is gone");

        send(HttpRequest.newBuilder(at(b, "/logout")).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(401, send(HttpRequest.newBuilder(at(b, "/me")).header("Cookie", cookie)).statusCode(),
                "logout ends the session everywhere");
    }
}
