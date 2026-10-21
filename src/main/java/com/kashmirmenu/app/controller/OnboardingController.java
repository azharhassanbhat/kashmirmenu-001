package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.service.OnboardingRules;
import com.kashmirmenu.app.service.OnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The "get started" checklist for the signed-in restaurant. Management only. */
@RestController
@RequestMapping("/api/v1/onboarding")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @Operation(summary = "Setup progress, worked out from what exists (never self-reported)")
    @GetMapping
    public OnboardingRules.Progress progress() {
        return onboardingService.progress();
    }
}
