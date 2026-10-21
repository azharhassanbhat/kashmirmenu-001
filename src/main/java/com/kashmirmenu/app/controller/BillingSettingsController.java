package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.BillingSettingsRequest;
import com.kashmirmenu.app.dto.response.BillingSettingsResponse;
import com.kashmirmenu.app.service.BillingSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The signed-in restaurant's service charge and tax rules. Management only; the restaurant is implied. */
@RestController
@RequestMapping("/api/v1/billing-settings")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class BillingSettingsController {

    private final BillingSettingsService billingSettingsService;

    @Operation(summary = "This restaurant's service charge and tax rules (zeros if never configured)")
    @GetMapping
    public BillingSettingsResponse get() {
        return billingSettingsService.get();
    }

    @Operation(summary = "Set this restaurant's service charge and tax rules")
    @PutMapping
    public BillingSettingsResponse save(@Valid @RequestBody BillingSettingsRequest request) {
        return billingSettingsService.save(request);
    }
}
