package com.gameflix.userservice.user;

import java.time.Instant;

/**
 * Public view of a user. Deliberately has no password or hash field,
 * so there is no way for the hash to end up in a response body.
 */
public record UserResponse(
        Long userId,
        String email,
        String username,
        String firstName,
        String lastName,
        String avatarUrl,
        String bio,
        String role,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
