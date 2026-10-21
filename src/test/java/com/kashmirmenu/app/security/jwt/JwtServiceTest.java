package com.kashmirmenu.app.security.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("unit-test-secret-key-at-least-32-bytes-long");
        properties.setAccessTokenExpiration(60_000);

        jwtService = new JwtService(properties);
        userDetails = new User("jwt.user@example.com", "password", java.util.List.of());
    }

    @Test
    void generateToken_canBeValidatedForSameUser() {
        String token = jwtService.generateToken(userDetails);

        assertThat(jwtService.extractUsername(token)).isEqualTo(userDetails.getUsername());
        assertThat(jwtService.isTokenValid(token, userDetails)).isTrue();
    }

    @Test
    void isTokenValid_falseForDifferentUser() {
        String token = jwtService.generateToken(userDetails);
        UserDetails otherUser = new User("someone.else@example.com", "password", java.util.List.of());

        assertThat(jwtService.isTokenValid(token, otherUser)).isFalse();
    }

    @Test
    void extractUsername_throwsForExpiredToken() {
        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret("unit-test-secret-key-at-least-32-bytes-long");
        expiredProperties.setAccessTokenExpiration(-60_000);

        JwtService expiredJwtService = new JwtService(expiredProperties);
        String token = expiredJwtService.generateToken(userDetails);

        assertThatThrownBy(() -> expiredJwtService.extractUsername(token))
                .isInstanceOf(ExpiredJwtException.class);
    }
}