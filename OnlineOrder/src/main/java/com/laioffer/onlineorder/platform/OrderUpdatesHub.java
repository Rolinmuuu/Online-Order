package com.laioffer.onlineorder.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Fans order updates out to the browsers connected to this instance.
 *
 * <p>One dedicated connection per instance (outside the request pool, so it can never starve
 * it and can bypass a transaction-mode pooler) runs {@code LISTEN order_updates}. Every committed
 * change, made by any instance, arrives here and is pushed to the matching Server-Sent Events
 * streams: a customer's own orders, or one restaurant's kitchen board.
 *
 * <ul>
 *   <li>Sending happens on a small pool, not on the listening thread, so one slow browser cannot
 *       hold up everyone else's updates.</li>
 *   <li>If no notification arrives for 10 s the connection is probed with {@code SELECT 1}; a dead
 *       one is replaced with backoff.</li>
 *   <li>Notifications sent while the listener was reconnecting are lost, so after every reconnect
 *       each open stream gets a {@code ready} event, which makes the client re-fetch. A missed
 *       notification therefore delays an update; it never leaves a screen wrong.</li>
 * </ul>
 */
@Component
public class OrderUpdatesHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderUpdatesHub.class);
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int PROBE_AFTER_IDLE_MS = 10_000;

    private record Subscriber(SseEmitter emitter, Predicate<OrderUpdates.Update> filter) {
    }

    private final DataSource dataSource;
    private final DataSourceProperties properties;
    private final ObjectMapper json;
    private final boolean enabled;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final List<Consumer<OrderUpdates.Update>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService senders = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "order-updates-sender");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private Thread thread;

    public OrderUpdatesHub(DataSource dataSource, ObjectProvider<DataSourceProperties> properties, ObjectMapper json,
                           @Value("${app.background-jobs.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.properties = properties.getIfAvailable();
        this.json = json;
        this.enabled = enabled;
    }

    /** Opens an SSE stream that receives the updates accepted by {@code filter}. */
    public SseEmitter subscribe(Predicate<OrderUpdates.Update> filter) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber s = new Subscriber(emitter, filter);
        subscribers.add(s);
        emitter.onCompletion(() -> subscribers.remove(s));
        emitter.onTimeout(() -> subscribers.remove(s));
        emitter.onError(e -> subscribers.remove(s));
        send(s, SseEmitter.event().name("ready").data("{}"));
        return emitter;
    }

    /** In-process listener (used by tests and metrics). */
    public void addListener(Consumer<OrderUpdates.Update> listener) {
        listeners.add(listener);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    void dispatch(OrderUpdates.Update update) {
        for (Consumer<OrderUpdates.Update> l : listeners) {
            l.accept(update);
        }
        for (Subscriber s : subscribers) {
            if (s.filter().test(update)) {
                senders.execute(() -> send(s, SseEmitter.event().name("order").data(update)));
            }
        }
    }

    private void send(Subscriber s, SseEmitter.SseEventBuilder event) {
        try {
            s.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(s);
        }
    }

    /** Keeps idle streams open through proxies that cut silent connections. */
    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        for (Subscriber s : subscribers) {
            senders.execute(() -> send(s, SseEmitter.event().comment("keep-alive")));
        }
    }

    private Connection openListenConnection() throws SQLException {
        if (properties != null && properties.getUrl() != null) {
            // A connection of its own, not borrowed from the pool for the lifetime of the app.
            return DriverManager.getConnection(properties.determineUrl(),
                    properties.determineUsername(), properties.determinePassword());
        }
        return dataSource.getConnection();
    }

    private void listenLoop() {
        long backoffMs = 500;
        boolean firstConnect = true;
        while (running) {
            try (Connection c = openListenConnection()) {
                c.setAutoCommit(true);
                try (Statement st = c.createStatement()) {
                    st.execute("LISTEN " + OrderUpdates.CHANNEL);
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                backoffMs = 500;
                if (!firstConnect) {
                    // We were deaf for a while: tell every open screen to re-fetch.
                    log.info("order update listener reconnected; asking {} streams to refresh", subscribers.size());
                    for (Subscriber s : subscribers) {
                        senders.execute(() -> send(s, SseEmitter.event().name("ready").data("{}")));
                    }
                }
                firstConnect = false;
                long lastHeard = System.currentTimeMillis();
                while (running) {
                    PGNotification[] batch = pg.getNotifications(1000);
                    if (batch != null && batch.length > 0) {
                        lastHeard = System.currentTimeMillis();
                        for (PGNotification n : batch) {
                            try {
                                dispatch(json.readValue(n.getParameter(), OrderUpdates.Update.class));
                            } catch (IOException | RuntimeException e) {
                                log.warn("bad order update payload: {}", n.getParameter(), e);
                            }
                        }
                    } else if (System.currentTimeMillis() - lastHeard > PROBE_AFTER_IDLE_MS) {
                        try (Statement probe = c.createStatement()) {
                            probe.execute("SELECT 1"); // throws if the connection silently died
                        }
                        lastHeard = System.currentTimeMillis();
                    }
                }
            } catch (SQLException e) {
                if (!running) {
                    return;
                }
                log.warn("order update listener lost its connection, retrying in {} ms", backoffMs, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::listenLoop, "order-updates-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        subscribers.forEach(s -> s.emitter().complete());
        subscribers.clear();
        senders.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
