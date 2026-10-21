package com.kashmirmenu.app.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.billing")
public class BillingProperties {

    /**
     * When true (the default), an order is billed and payable only once the kitchen has confirmed it:
     * a PENDING order is owed but waits. Set false for a restaurant that does not use the confirm
     * step, so guests can pay as soon as they have ordered.
     */
    private boolean requireConfirmedOrders = true;
}
