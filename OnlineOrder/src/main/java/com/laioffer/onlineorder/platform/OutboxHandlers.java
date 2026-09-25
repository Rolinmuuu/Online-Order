package com.laioffer.onlineorder.platform;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects every {@link OutboxTopicHandler} bean, one per topic. */
@Component
public class OutboxHandlers {

    private final Map<String, OutboxTopicHandler> byTopic = new HashMap<>();

    public OutboxHandlers(List<OutboxTopicHandler> handlers) {
        for (OutboxTopicHandler h : handlers) {
            if (byTopic.put(h.topic(), h) != null) {
                throw new IllegalStateException("two outbox handlers for topic " + h.topic());
            }
        }
    }

    public OutboxTopicHandler forTopic(String topic) {
        return byTopic.get(topic);
    }
}
