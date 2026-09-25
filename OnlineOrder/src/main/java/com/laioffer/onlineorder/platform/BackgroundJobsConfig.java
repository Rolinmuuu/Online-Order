package com.laioffer.onlineorder.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the background loops (outbox dispatcher, expiry sweeper, SSE heartbeat, housekeeping).
 * {@code app.background-jobs.enabled=false} switches them off, e.g. for a test context that
 * shares a database with other tests, or for an instance that should only serve requests.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.background-jobs.enabled", havingValue = "true", matchIfMissing = true)
public class BackgroundJobsConfig {
}
