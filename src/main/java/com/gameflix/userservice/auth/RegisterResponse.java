package com.gameflix.userservice.auth;

import com.gameflix.userservice.user.User;

import java.time.Instant;

/** Matches the register response from my Module 3 User Service design. No hash. */
public record RegisterResponse(Long userId, String email, String username, Instant createdAt) {

    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getEmail(), user.getUsername(), user.getCreatedAt());
    }
}
