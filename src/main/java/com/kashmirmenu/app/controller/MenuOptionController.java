package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.MenuOptionsRequest;
import com.kashmirmenu.app.dto.request.UpdateOptionAvailabilityRequest;
import com.kashmirmenu.app.dto.response.OptionGroupResponse;
import com.kashmirmenu.app.service.MenuOptionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Portion sizes, add-ons and spice levels for one dish. Management only; scoped to the caller's restaurant. */
@RestController
@RequestMapping("/api/menu-items/{menuItemId}/options")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class MenuOptionController {

    private final MenuOptionService menuOptionService;

    @Operation(summary = "The dish's option groups, including options currently switched off")
    @GetMapping
    public List<OptionGroupResponse> get(@PathVariable Long menuItemId) {
        return menuOptionService.get(menuItemId);
    }

    @Operation(summary = "Replace the dish's option groups (reconciled by id)")
    @PutMapping
    public List<OptionGroupResponse> replace(
            @PathVariable Long menuItemId,
            @Valid @RequestBody MenuOptionsRequest request) {
        return menuOptionService.replace(menuItemId, request);
    }

    @Operation(summary = "Mark one option available or sold out")
    @PutMapping("/{optionId}/availability")
    public List<OptionGroupResponse> setAvailability(
            @PathVariable Long menuItemId,
            @PathVariable Long optionId,
            @Valid @RequestBody UpdateOptionAvailabilityRequest request) {
        return menuOptionService.setAvailability(menuItemId, optionId, request.available());
    }
}
