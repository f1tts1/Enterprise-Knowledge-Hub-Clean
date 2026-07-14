package com.example.ekb.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "username is required")
        @Size(min = 3, max = 64, message = "username length must be between 3 and 64")
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password length must be between 8 and 72")
        String password,

        @Size(max = 64, message = "nickname length must be at most 64")
        String nickname,

        @Email(message = "email format is invalid")
        @Size(max = 128, message = "email length must be at most 128")
        String email
) {
}
