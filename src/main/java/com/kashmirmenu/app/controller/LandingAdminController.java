package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.LandingSettingsRequest;
import com.kashmirmenu.app.dto.response.AdminLandingResponse;
import com.kashmirmenu.app.dto.response.AnonymousFeedbackEntryResponse;
import com.kashmirmenu.app.service.landing.LandingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The owner's side of the landing page. The restaurant is always the signed-in user's own, so there is no id in
 * any path to tamper with. The Wi-Fi password is never returned: only whether one is saved.
 */
@RestController
@RequestMapping("/api/v1/landing")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class LandingAdminController {

    private final LandingService landingService;

    @Operation(summary = "The restaurant's landing-page settings (never includes the Wi-Fi password)")
    @GetMapping
    public AdminLandingResponse get() {
        return landingService.get();
    }

    @Operation(summary = "Save the landing-page settings. A null wifiPassword keeps the saved one.")
    @PutMapping
    public AdminLandingResponse save(@Valid @RequestBody LandingSettingsRequest request) {
        return landingService.save(request);
    }

    @Operation(summary = "Feedback left from the landing page, newest first")
    @GetMapping("/feedback")
    public PageResponse<AnonymousFeedbackEntryResponse> feedback(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return landingService.feedback(page, size);
    }
}
