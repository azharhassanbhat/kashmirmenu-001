package com.kashmirmenu.app.config;

import com.kashmirmenu.app.enums.RestaurantStatus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.restaurant-approval")
public class RestaurantApprovalProperties {

    /**
     * When true (the default) a newly registered restaurant starts PENDING and cannot operate until
     * the platform owner approves it. When false it starts APPROVED, as registration behaved before
     * approval existed. Enforcement of an existing status does not depend on this switch.
     */
    private boolean required = true;

    /** The status a restaurant registered right now starts in. */
    public RestaurantStatus initialStatus() {
        return required ? RestaurantStatus.PENDING : RestaurantStatus.APPROVED;
    }
}
