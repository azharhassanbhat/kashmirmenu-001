package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.BillingSettingsRequest;
import com.kashmirmenu.app.dto.response.BillingSettingsResponse;
import com.kashmirmenu.app.entity.RestaurantBillingSetting;
import com.kashmirmenu.app.repository.RestaurantBillingSettingRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Each restaurant's own service charge and tax rules. With no settings saved, or with zeros, a
 * restaurant charges neither and its totals are exactly what they were before this existed.
 *
 * <p>The caller's restaurant is always taken from the signed-in user, never from the request, so one
 * restaurant cannot read or change another's rates.
 */
@Service
@RequiredArgsConstructor
public class BillingSettingsService {

    /** What the pricing code needs: no entity, so it is safe to hold and easy to test. */
    public record Rules(String taxName, BigDecimal taxRate, BigDecimal serviceChargeRate, boolean pricesIncludeTax) {
        public static final Rules NONE = new Rules("Tax", BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    private final RestaurantBillingSettingRepository settings;
    private final CurrentUserService currentUserService;

    /** The rules in force for a restaurant; {@link Rules#NONE} when it has never configured any. */
    @Transactional(readOnly = true)
    public Rules rulesFor(Long restaurantId) {
        return settings.findByRestaurantId(restaurantId)
                .map(s -> new Rules(
                        s.getTaxName(),
                        s.getTaxRate(),
                        s.getServiceChargeRate(),
                        Boolean.TRUE.equals(s.getPricesIncludeTax())))
                .orElse(Rules.NONE);
    }

    /** The registration number to print on receipts, or null when the restaurant has none. */
    @Transactional(readOnly = true)
    public String taxRegistrationFor(Long restaurantId) {
        return settings.findByRestaurantId(restaurantId)
                .map(RestaurantBillingSetting::getTaxRegistrationNumber)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public BillingSettingsResponse get() {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        return toResponse(rulesFor(restaurantId), taxRegistrationFor(restaurantId));
    }

    @Transactional
    public BillingSettingsResponse save(BillingSettingsRequest request) {
        var restaurant = currentUserService.getCurrentRestaurant();

        RestaurantBillingSetting row = settings.findByRestaurantId(restaurant.getId()).orElseGet(() -> {
            RestaurantBillingSetting created = new RestaurantBillingSetting();
            created.setRestaurant(restaurant);
            return created;
        });

        row.setTaxName(request.taxName().strip());
        row.setTaxRate(request.taxRate());
        row.setServiceChargeRate(request.serviceChargeRate());
        row.setPricesIncludeTax(request.pricesIncludeTax());
        // Tidied and checked for safe printing (not verified as genuine). Blank clears it: this is a full
        // replacement, like the rest of the settings.
        row.setTaxRegistrationNumber(TaxRegistration.clean(request.taxRegistrationNumber()));
        settings.save(row);

        return toResponse(new Rules(
                row.getTaxName(), row.getTaxRate(), row.getServiceChargeRate(), row.getPricesIncludeTax()),
                row.getTaxRegistrationNumber());
    }

    private static BillingSettingsResponse toResponse(Rules r, String taxRegistrationNumber) {
        return new BillingSettingsResponse(
                r.taxName(), r.taxRate(), r.serviceChargeRate(), r.pricesIncludeTax(), taxRegistrationNumber);
    }
}
