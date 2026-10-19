package com.gameflix.userservice.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spring Security 6 style: a SecurityFilterChain bean configured with lambdas.
 * (No WebSecurityConfigurerAdapter; it was removed in Spring Security 6.)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // ------------------------------------------------------------------ filter chain

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protects cookie-based sessions. This API is stateless and the token is sent
                // in the Authorization header (never a cookie), so the browser can't attach it on
                // a forged cross-site request. Disabling CSRF is safe here.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())   // uses the corsConfigurationSource bean below
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // Bearer-token filter: verifies signature + exp + iss BEFORE the request reaches a
                // controller. Missing, tampered, or expired token -> 401.
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    // ------------------------------------------------------------------ password hashing

    /**
     * bcrypt, work factor 12, wrapped in a DelegatingPasswordEncoder.
     * Stored hashes look like {bcrypt}$2a$12$..., so the prefix records which algorithm
     * made each hash and we can migrate algorithms later while still verifying old ones.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        String defaultId = "bcrypt";
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(defaultId, new BCryptPasswordEncoder(12));
        return new DelegatingPasswordEncoder(defaultId, encoders);
    }

    // ------------------------------------------------------------------ JWT signing

    /**
     * The HMAC key comes from config (application-local.properties or the APP_JWT_SECRET
     * environment variable). It is never in source control. Fails fast at startup if the
     * key is missing or too short for HS256.
     */
    @Bean
    public SecretKey jwtSigningKey(@Value("${app.jwt.secret}") String base64Secret) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret.trim());
        if (keyBytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be Base64 for at least 32 bytes (256 bits) for HS256");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /**
     * Verifies every incoming token: HS256 signature with our key, then the default
     * timestamp check (exp/nbf), issuer, and an explicit "exp must exist" rule.
     * The default timestamp validator lets a token with NO exp through, so we require it.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, @Value("${app.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> requireExp =
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                requireExp));
        return decoder;
    }

    // ------------------------------------------------------------------ CORS

    /** Only the configured front-end origin is allowed. Never "*". */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origin}") String allowedOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
