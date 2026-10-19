package com.gameflix.userservice.auth;

import com.gameflix.userservice.error.DuplicateAccountException;
import com.gameflix.userservice.error.InvalidCredentialsException;
import com.gameflix.userservice.error.InvalidInputException;
import com.gameflix.userservice.user.User;
import com.gameflix.userservice.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    /** One message for every login failure, so attackers can't learn which emails exist. */
    static final String LOGIN_FAILED = "Invalid email or password";

    /** bcrypt ignores everything past 72 bytes; Spring Security rejects longer input. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /**
     * A hash of a random value, used when the email doesn't exist. We still run
     * passwordEncoder.matches() against it so "unknown email" takes about as long as
     * "wrong password". Otherwise response time alone would reveal registered emails.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * No @Transactional here on purpose: saveAndFlush runs in its own transaction, so a
     * unique-constraint violation surfaces right here as DataIntegrityViolationException.
     * The 409 comes from the database constraint, not from a "does it exist?" query.
     */
    public User register(RegisterRequest req) {
        if (tooLongForBcrypt(req.password())) {
            throw new InvalidInputException("Password must be at most 72 bytes");
        }

        User user = new User(
                normalizeEmail(req.email()),
                req.username().trim(),
                passwordEncoder.encode(req.password()),   // hash BEFORE it ever reaches the entity
                req.firstName().trim(),
                req.lastName().trim()
        );

        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("uk_users_username")) {
                throw new DuplicateAccountException("Username is already taken");
            }
            throw new DuplicateAccountException("Email is already registered");
        }
    }

    public LoginResponse login(LoginRequest req) {
        String email = req.email() == null ? null : normalizeEmail(req.email());
        String password = req.password();

        if (email == null || password == null || tooLongForBcrypt(password)) {
            passwordEncoder.matches("x", dummyHash);   // keep timing consistent
            throw new InvalidCredentialsException(LOGIN_FAILED);
        }

        Optional<User> found = users.findByEmail(email);
        String hashToCheck = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(password, hashToCheck);

        if (found.isEmpty() || !passwordMatches || !found.get().isEnabled()) {
            throw new InvalidCredentialsException(LOGIN_FAILED);
        }

        User user = found.get();
        user.setLastLoginAt(Instant.now());
        users.save(user);

        return new LoginResponse(tokenService.issue(user), "Bearer", tokenService.ttlSeconds(), user.getId());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean tooLongForBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}
