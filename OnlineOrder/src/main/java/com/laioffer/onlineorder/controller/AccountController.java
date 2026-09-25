package com.laioffer.onlineorder.controller;

import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.service.CustomerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AccountController {

    private final CustomerService customerService;
    private final JdbcTemplate jdbc;

    public AccountController(CustomerService customerService, JdbcTemplate jdbc) {
        this.customerService = customerService;
        this.jdbc = jdbc;
    }

    /** Who is signed in (401 if nobody). The client uses it to restore a session on reload. */
    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal User user) {
        CustomerEntity c = customerService.getCustomerByEmail(user.getUsername());
        Integer staffOf = jdbc.queryForObject(
                "SELECT count(*) FROM restaurant_staff WHERE email = ?", Integer.class, c.email());
        return Map.of(
                "email", c.email(),
                "first_name", c.firstName() == null ? "" : c.firstName(),
                "kitchen_staff", staffOf != null && staffOf > 0);
    }
}
