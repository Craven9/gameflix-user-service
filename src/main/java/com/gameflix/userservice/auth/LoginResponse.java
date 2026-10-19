package com.gameflix.userservice.auth;

/**
 * @param accessToken signed JWT to send as "Authorization: Bearer <token>"
 * @param tokenType   always "Bearer"
 * @param expiresIn   token lifetime in seconds
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, Long userId) {
}
