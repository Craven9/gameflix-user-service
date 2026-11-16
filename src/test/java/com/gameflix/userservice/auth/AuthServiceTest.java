package com.gameflix.userservice.auth;

import com.gameflix.userservice.error.DuplicateAccountException;
import com.gameflix.userservice.error.InvalidCredentialsException;
import com.gameflix.userservice.error.InvalidInputException;
import com.gameflix.userservice.user.User;
import com.gameflix.userservice.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthService (JUnit 5 + Mockito).
 * The repository and token service are mocked, so these tests run without MySQL
 * or a JWT secret. The password encoder is a real BCrypt encoder (low cost factor
 * to keep the tests fast), so hashing is actually exercised.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private TokenService tokenService;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        authService = new AuthService(users, passwordEncoder, tokenService);
    }

    @Test
    void register_ShouldHashPasswordAndNormalizeEmail() {
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        RegisterRequest req = new RegisterRequest(
                "  Cole@Example.COM ", "password123", " cole_c ", " Cole ", " Craven ");

        User saved = authService.register(req);

        assertNotNull(saved);
        assertEquals("cole@example.com", saved.getEmail());       // trimmed + lowercased
        assertEquals("cole_c", saved.getUsername());               // trimmed
        assertNotEquals("password123", saved.getPasswordHash());   // never stored in plain text
        assertTrue(passwordEncoder.matches("password123", saved.getPasswordHash()));
    }

    @Test
    void register_DuplicateUsername_ShouldThrowDuplicateAccountException() {
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("Duplicate entry 'cole_c' for key 'users.uk_users_username'")));

        RegisterRequest req = new RegisterRequest(
                "cole@example.com", "password123", "cole_c", "Cole", "Craven");

        DuplicateAccountException ex = assertThrows(DuplicateAccountException.class,
                () -> authService.register(req));
        assertEquals("Username is already taken", ex.getMessage());
    }

    @Test
    void register_PasswordOver72Bytes_ShouldBeRejected() {
        RegisterRequest req = new RegisterRequest(
                "cole@example.com", "a".repeat(73), "cole_c", "Cole", "Craven");

        assertThrows(InvalidInputException.class, () -> authService.register(req));
        verify(users, never()).saveAndFlush(any());               // nothing reaches the database
    }

    @Test
    void login_WrongPassword_ShouldThrowGenericError() {
        User user = new User("cole@example.com", "cole_c",
                passwordEncoder.encode("correctPassword"), "Cole", "Craven");
        when(users.findByEmail("cole@example.com")).thenReturn(Optional.of(user));

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("cole@example.com", "wrongPassword")));

        assertEquals("Invalid email or password", ex.getMessage());
        verify(tokenService, never()).issue(any());                // no token for a bad login
    }

    @Test
    void login_UnknownEmail_ShouldThrowSameGenericError() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("nobody@example.com", "whatever123")));

        assertEquals("Invalid email or password", ex.getMessage()); // same message as wrong password
    }

    @Test
    void login_ValidCredentials_ShouldReturnBearerToken() {
        User user = new User("cole@example.com", "cole_c",
                passwordEncoder.encode("password123"), "Cole", "Craven");
        when(users.findByEmail("cole@example.com")).thenReturn(Optional.of(user));
        when(tokenService.issue(user)).thenReturn("fake.jwt.token");
        when(tokenService.ttlSeconds()).thenReturn(1800L);

        LoginResponse res = authService.login(new LoginRequest(" COLE@example.com ", "password123"));

        assertEquals("fake.jwt.token", res.accessToken());
        assertEquals("Bearer", res.tokenType());
        assertEquals(1800L, res.expiresIn());
        assertNotNull(user.getLastLoginAt());                      // last login time recorded
        verify(users).save(user);
    }
}
