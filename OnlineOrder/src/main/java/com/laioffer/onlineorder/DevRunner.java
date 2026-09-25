package com.laioffer.onlineorder;

import com.laioffer.onlineorder.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Demo accounts only in demo mode (APP_DEMO=true, the default for local runs).
@Component
@ConditionalOnProperty(name = "app.demo", havingValue = "true", matchIfMissing = true)
public class DevRunner implements ApplicationRunner {


    private static final Logger logger = LoggerFactory.getLogger(DevRunner.class);


    private final CustomerService customerService;
    private final JdbcTemplate jdbc;


    public DevRunner(
            CustomerService customerService,
            JdbcTemplate jdbc) {
        this.customerService = customerService;
        this.jdbc = jdbc;
    }


    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Demo accounts. Skipped when they already exist (the database keeps its data across restarts).
        if (customerService.getCustomerByEmail("foo@mail.com") == null) {
            customerService.signUp("foo@mail.com", "123456", "Foo", "Bar");
        }
        if (customerService.getCustomerByEmail("kitchen@mail.com") == null) {
            customerService.signUp("kitchen@mail.com", "123456", "Kitchen", "Staff");
            // Staff of every sample restaurant, so one account can demo all kitchen boards.
            jdbc.update("INSERT INTO restaurant_staff (email, restaurant_id) SELECT 'kitchen@mail.com', id FROM restaurants");
            logger.info("demo accounts: foo@mail.com (customer), kitchen@mail.com (kitchen staff), password 123456");
        }
    }
}
