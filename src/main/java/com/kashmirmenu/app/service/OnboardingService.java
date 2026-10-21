package com.kashmirmenu.app.service;

import com.kashmirmenu.app.entity.RestaurantPaymentSetting;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.repository.CategoryRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.RestaurantPaymentSettingRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.repository.UserRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** How far the signed-in restaurant has got towards taking real orders, from what actually exists. */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Set<RoleName> STAFF_ROLES =
            EnumSet.of(RoleName.MANAGER, RoleName.CHEF, RoleName.WAITER, RoleName.CASHIER);

    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final RestaurantTableRepository tables;
    private final OrderRepository orders;
    private final RestaurantPaymentSettingRepository paymentSettings;
    private final UserRepository users;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public OnboardingRules.Progress progress() {
        Long restaurantId = currentUserService.getCurrentRestaurantId();

        boolean paymentConfigured = paymentSettings.findByRestaurantId(restaurantId)
                .map(OnboardingService::anyMethodEnabled)
                .orElse(false);
        long team = users.findByRestaurantIdOrderByNameAsc(restaurantId).stream()
                .filter(u -> u.getRole() != null && STAFF_ROLES.contains(u.getRole().getName()))
                .count();

        return OnboardingRules.evaluate(new OnboardingRules.Counts(
                categories.countByRestaurantIdAndActiveTrue(restaurantId),
                items.countByRestaurantIdAndActiveTrueAndAvailableTrue(restaurantId),
                tables.countByRestaurantIdAndActiveTrue(restaurantId),
                paymentConfigured,
                orders.countByRestaurantId(restaurantId),
                team));
    }

    private static boolean anyMethodEnabled(RestaurantPaymentSetting setting) {
        return Arrays.stream(PaymentMethod.values()).anyMatch(setting::isPaymentMethodEnabled);
    }
}
