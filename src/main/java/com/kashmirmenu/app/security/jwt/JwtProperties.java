package com.kashmirmenu.app.security.jwt;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;

    private long accessTokenExpiration;

    @PostConstruct
    void validate() {
        if (secret == null
                || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be set and at least 32 bytes (256 bits) " +
                            "for HS256 signing. Set the JWT_SECRET environment variable.");
        }
        // A key of the right length can still be a published one. Refuse the specific placeholders
        // this repository has carried, so a copied dev config cannot sign real tokens.
        //
        // Deliberately narrow. An earlier version also banned "secret-key" and "example", which are
        // substrings of plenty of legitimate values - including this project's own test fixture, so
        // every integration test failed at context startup. A guard that rejects valid secrets does
        // more damage than one that is a little less thorough: it blocks the build rather than a
        // deployment, and the real protection is that prod has no default at all.
        String normalised = secret.toLowerCase();
        for (String banned : new String[]{"dev-only", "change-me", "replace-with", "insecure", "placeholder"}) {
            if (normalised.contains(banned)) {
                throw new IllegalStateException(
                        "jwt.secret looks like a placeholder. Generate a real one, e.g. "
                                + "openssl rand -base64 48, and set JWT_SECRET.");
            }
        }
        if (accessTokenExpiration <= 0) {
            throw new IllegalStateException(
                    "jwt.access-token-expiration must be a positive number of milliseconds.");
        }
    }
}