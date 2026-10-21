package com.kashmirmenu.app.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.kashmirmenu.app.security.CustomUserDetails;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public final class JwtService {

    private final JwtProperties jwtProperties;

    private SecretKey getSigningKey() {

        return Keys.hmacShaKeyFor(
                jwtProperties.getSecret()
                        .getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserDetails userDetails) {

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(
                        System.currentTimeMillis()
                                + jwtProperties.getAccessTokenExpiration()))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {

        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(
            String token,
            Function<Claims, T> claimsResolver) {

        Claims claims = extractAllClaims(token);

        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {

        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private boolean isTokenExpired(String token) {

        return extractClaim(
                token,
                Claims::getExpiration
        ).before(new Date());
    }

    public boolean isTokenValid(
            String token,
            UserDetails userDetails) {

        String username = extractUsername(token);

        return username.equals(userDetails.getUsername())
                && !isTokenExpired(token)
                && !isRevoked(token, userDetails);
    }

    /**
     * True when the token predates the user's revocation point. Logging out moves that point to now,
     * which invalidates every token issued before it - so a token copied from a shared device stops
     * working immediately instead of lasting until it expires.
     *
     * <p>Comparison is at second precision because that is all a JWT {@code iat} carries. A token
     * minted in the same second as the logout can therefore survive it; a fresh login in that second
     * must keep working, and preferring that costs at most one second of overlap.
     */
    private boolean isRevoked(String token, UserDetails userDetails) {

        if (!(userDetails instanceof CustomUserDetails details)) {
            return false;
        }
        LocalDateTime validFrom = details.getTokensValidFrom();
        if (validFrom == null) {
            return false;
        }

        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        if (issuedAt == null) {
            // No iat means the age cannot be established, so it cannot be trusted after a revocation.
            return true;
        }

        LocalDateTime issued = LocalDateTime.ofInstant(
                issuedAt.toInstant(), ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);

        return issued.isBefore(validFrom.truncatedTo(ChronoUnit.SECONDS));
    }
}