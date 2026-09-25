package com.laioffer.onlineorder.platform;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Explicit transaction boundaries for the order, payment and inventory modules.
 *
 * <p>Every unit of work runs in READ COMMITTED. Correctness under concurrency does not come from
 * a stricter isolation level but from the statements themselves: conditional updates
 * ({@code UPDATE ... WHERE available >= ?}), guarded state transitions
 * ({@code UPDATE ... WHERE status = ?}), row locks taken in a fixed order, and unique
 * constraints. See docs/ARCHITECTURE.md, "Why READ COMMITTED".
 *
 * <p>If PostgreSQL still aborts the transaction because of a deadlock (40P01) or a
 * serialization failure (40001) — which the lock ordering is meant to prevent — the whole unit of
 * work is retried a few times with jittered backoff. Domain errors (out of stock, invalid
 * transition) are never retried.
 */
@Component
public class Tx {

    static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate template;
    private final int maxAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public Tx(PlatformTransactionManager transactionManager) {
        this(transactionManager, MAX_ATTEMPTS);
    }

    /** {@code maxAttempts = 1} disables the retry (tests use it to make deadlocks visible). */
    public Tx(PlatformTransactionManager transactionManager, int maxAttempts) {
        this.maxAttempts = maxAttempts;
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (ConcurrencyFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                sleepQuietly(ThreadLocalRandom.current().nextLong(5, 25) * attempt);
            }
        }
    }

    public void runVoid(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
