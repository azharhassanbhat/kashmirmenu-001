package com.kashmirmenu.app.platform;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform administration.
 *
 * <p>Deliberately NOT annotated at class level with a role, because {@code /claim} has to be
 * reachable before any platform owner can sign in. Every other method here carries
 * {@code hasRole('PLATFORM_OWNER')} explicitly, and the filter chain states the same rule for
 * {@code /api/platform-admin/**} with {@code /claim} carved out — so a method added here without an
 * annotation is still refused by the chain rather than left open.
 */
@RestController
@RequestMapping("/api/platform-admin")
@RequiredArgsConstructor
public class PlatformAdminController {

    private final PlatformClaimService platformClaimService;

    @Operation(summary = "Claim the bootstrapped platform owner account (single use)")
    @PostMapping("/claim")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void claim(@Valid @RequestBody PlatformClaimRequest request) {

        platformClaimService.claim(request);
    }
}
