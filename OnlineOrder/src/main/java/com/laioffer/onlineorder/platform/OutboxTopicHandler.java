package com.laioffer.onlineorder.platform;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Delivers the outbox messages of one topic. Handlers must be idempotent on the outbox id:
 * a message can be delivered more than once (see {@link OutboxDispatcher}).
 */
public interface OutboxTopicHandler {

    String topic();

    void handle(long outboxId, JsonNode payload);

    /**
     * true (default): the handler only writes to this database, so it runs in the same
     * transaction that marks the message delivered — exactly-once.
     * false: it calls another system, so it runs outside any database transaction (no row locks
     * or pooled connection held while waiting on the network) — at-least-once.
     */
    default boolean transactional() {
        return true;
    }
}
