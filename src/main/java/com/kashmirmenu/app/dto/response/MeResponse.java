package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.enums.RoleName;

public record MeResponse(
        Long id,
        String name,
        String email,
        RoleName role,
        Long restaurantId,
        /** Null for accounts without a restaurant (customers, the platform owner). */
        RestaurantStatus restaurantStatus,
        /** The rejection or suspension reason matching restaurantStatus; null otherwise. */
        String restaurantStatusReason) {
}
