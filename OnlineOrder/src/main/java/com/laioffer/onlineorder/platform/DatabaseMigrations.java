package com.laioffer.onlineorder.platform;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Schema changes are Flyway migrations (db/migration), applied at startup before anything else
 * touches the database. Sample data is a repeatable migration in db/seed, included through
 * {@code spring.flyway.locations}.
 *
 * <p>{@code app.db.reset-on-start=true} wipes the schema first, which gives a demo fresh stock on
 * every start. It destroys data, so it is refused unless demo mode is on as well.
 */
@Configuration
public class DatabaseMigrations {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrations.class);

    @Bean
    public FlywayMigrationStrategy migrationStrategy(@Value("${app.db.reset-on-start:false}") boolean reset,
                                                     @Value("${app.demo:true}") boolean demo) {
        if (reset && !demo) {
            throw new IllegalStateException("app.db.reset-on-start deletes all data and is only allowed in demo mode");
        }
        return flyway -> {
            if (reset) {
                log.warn("app.db.reset-on-start: dropping every object in the schema before migrating");
                Flyway.configure().configuration(flyway.getConfiguration()).cleanDisabled(false).load().clean();
            }
            flyway.migrate();
        };
    }
}
