package com.gameflix.userservice.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank @Email @Size(max = 255)
        String email,

        // bcrypt only reads the first 72 bytes, so cap at 64 characters (assignment requirement).
        @NotBlank @Size(min = 8, max = 64)
        String password,

        @NotBlank @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "may only contain letters, numbers, '_', '.', and '-'")
        String username,

        @NotBlank @Size(max = 50)
        String firstName,

        @NotBlank @Size(max = 50)
        String lastName
) {
    // Records generate a toString() that prints every field. Override it so the
    // password can never end up in a log line if this object is ever logged.
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", username=" + username + ", password=****]";
    }
}
