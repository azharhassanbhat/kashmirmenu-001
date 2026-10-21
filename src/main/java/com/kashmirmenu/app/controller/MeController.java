package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.response.MeResponse;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final RestaurantRepository restaurantRepository;

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal CustomUserDetails principal) {

        Long restaurantId = principal.getRestaurantId();

        // Read fresh each time: this is how an owner sees a decision the platform owner just made.
        Restaurant restaurant = restaurantId == null
                ? null
                : restaurantRepository.findById(restaurantId).orElse(null);

        RestaurantStatus status = restaurant == null ? null : restaurant.getApprovalStatus();
        String reason = status == RestaurantStatus.REJECTED ? restaurant.getRejectionReason()
                : status == RestaurantStatus.SUSPENDED ? restaurant.getSuspensionReason()
                : null;

        return new MeResponse(
                principal.getUser().getId(),
                principal.getUser().getName(),
                principal.getUser().getEmail(),
                principal.getUser().getRole().getName(),
                restaurantId,
                status,
                reason);
    }
}
