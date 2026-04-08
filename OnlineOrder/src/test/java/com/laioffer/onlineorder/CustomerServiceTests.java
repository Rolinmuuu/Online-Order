package com.laioffer.onlineorder;

import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.CustomerEntity;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.CustomerRepository;
import com.laioffer.onlineorder.service.CustomerService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;


@ExtendWith(MockitoExtension.class)
public class CustomerServiceTests {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserDetailsManager userDetailsManager;

    private CustomerService customerService;


    @BeforeEach
    void setup() {
        customerService = new CustomerService(cartRepository, customerRepository, passwordEncoder, userDetailsManager);
    }


    @Test
    void signUp_shouldCreateUserAndInitializeCart() {
        String email = "test@example.com";
        String password = "secret";
        String firstName = "John";
        String lastName = "Doe";
        long customerId = 1L;

        Mockito.when(passwordEncoder.encode(password)).thenReturn("{bcrypt}hashedpw");
        CustomerEntity savedCustomer = new CustomerEntity(customerId, email, "{bcrypt}hashedpw", true, firstName, lastName);
        Mockito.when(customerRepository.findByEmail(email)).thenReturn(savedCustomer);

        customerService.signUp(email, password, firstName, lastName);

        Mockito.verify(userDetailsManager).createUser(Mockito.argThat(u -> u.getUsername().equals(email)));
        Mockito.verify(customerRepository).updateNameByEmail(email, firstName, lastName);
        CartEntity expectedCart = new CartEntity(null, customerId, 0.0, null);
        Mockito.verify(cartRepository).save(expectedCart);
    }


    @Test
    void signUp_shouldNormalizeEmailToLowercase() {
        String mixedEmail = "Test@Example.COM";
        String normalizedEmail = "test@example.com";
        String password = "secret";
        long customerId = 2L;

        Mockito.when(passwordEncoder.encode(password)).thenReturn("{bcrypt}hashedpw");
        CustomerEntity savedCustomer = new CustomerEntity(customerId, normalizedEmail, "{bcrypt}hashedpw", true, "A", "B");
        Mockito.when(customerRepository.findByEmail(normalizedEmail)).thenReturn(savedCustomer);

        customerService.signUp(mixedEmail, password, "A", "B");

        Mockito.verify(userDetailsManager).createUser(Mockito.argThat(u -> u.getUsername().equals(normalizedEmail)));
    }


    @Test
    void getCustomerByEmail_shouldReturnCustomer() {
        String email = "user@example.com";
        CustomerEntity expected = new CustomerEntity(1L, email, "pw", true, "Jane", "Doe");
        Mockito.when(customerRepository.findByEmail(email)).thenReturn(expected);

        CustomerEntity result = customerService.getCustomerByEmail(email);

        Assertions.assertEquals(expected, result);
        Mockito.verify(customerRepository).findByEmail(email);
    }
}
