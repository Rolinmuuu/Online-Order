package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.platform.DatabaseMigrations;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationsIT extends PostgresTestSupport {

    @Test
    void migratingAnUpToDateDatabaseChangesNothing() {
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertTrue(flyway.validateWithResult().validationSuccessful);
    }

    /** Flyway re-runs the repeatable seed whenever it changes, on databases that already hold data. */
    @Test
    void theSampleMenuCanBeAppliedAgainWithoutDuplicatesOrRestocking() {
        int restaurants = count("SELECT count(*) FROM restaurants");
        int items = count("SELECT count(*) FROM menu_items");
        jdbc.update("UPDATE inventory SET available = 3 WHERE menu_item_id = 2"); // the day's sales

        new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__sample_menu.sql")).execute(dataSource);

        assertEquals(restaurants, count("SELECT count(*) FROM restaurants"));
        assertEquals(items, count("SELECT count(*) FROM menu_items"));
        assertEquals(3, stock(2));
    }

    @Test
    void rowsAddedAfterTheSeedDoNotCollideWithItsFixedIds() {
        long id = jdbc.queryForObject(
                "INSERT INTO restaurants (name) VALUES ('New Place') RETURNING id", Long.class);
        assertTrue(id > 3);
    }

    @Test
    void resettingTheDatabaseIsRefusedOutsideDemoMode() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DatabaseMigrations().migrationStrategy(true, false));
        assertTrue(e.getMessage().contains("demo"));
    }
}
