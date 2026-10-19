package com.gameflix.userservice.user;

import com.gameflix.userservice.error.InvalidCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /**
     * Protected route. By the time this runs, Spring Security's bearer-token filter has
     * already verified the JWT's signature, exp, and iss; a missing, tampered, or expired
     * token never reaches this method (it gets 401 in the filter chain).
     *
     * The user is identified ONLY from the token's "sub" claim, never from a URL or
     * request parameter, so a caller cannot ask for someone else's profile.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return users.findById(userId)
                .filter(User::isEnabled)
                .map(UserResponse::from)
                // Valid token but the account was deleted or disabled after it was issued.
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired token"));
    }
}
