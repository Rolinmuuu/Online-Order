package com.laioffer.onlineorder.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterBody(
        @NotBlank @Email @Size(max = 254) String email,
        // Length is what makes a password hard to guess; the upper bound caps hashing cost.
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName
) {

}
