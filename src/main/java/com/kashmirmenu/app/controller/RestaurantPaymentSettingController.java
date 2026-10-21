package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.CreateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.response.RestaurantPaymentSettingResponse;
import com.kashmirmenu.app.service.RestaurantPaymentSettingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantId}/payment-settings")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class RestaurantPaymentSettingController {

    private final RestaurantPaymentSettingService paymentSettingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create payment settings")
    public RestaurantPaymentSettingResponse createPaymentSetting(
            @PathVariable Long restaurantId,
            @Valid @RequestBody CreateRestaurantPaymentSettingRequest request) {

        return paymentSettingService.createPaymentSetting(
                restaurantId,
                request
        );
    }

    @GetMapping
    @Operation(summary = "Get payment settings")
    public RestaurantPaymentSettingResponse getPaymentSetting(
            @PathVariable Long restaurantId) {

        return paymentSettingService.getPaymentSetting(
                restaurantId
        );
    }

    @PutMapping
    @Operation(summary = "Update payment settings")
    public RestaurantPaymentSettingResponse updatePaymentSetting(
            @PathVariable Long restaurantId,
            @Valid @RequestBody UpdateRestaurantPaymentSettingRequest request) {

        return paymentSettingService.updatePaymentSetting(
                restaurantId,
                request
        );
    }
}