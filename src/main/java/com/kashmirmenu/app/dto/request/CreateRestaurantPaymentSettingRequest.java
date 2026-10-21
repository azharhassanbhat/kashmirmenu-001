package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateRestaurantPaymentSettingRequest {

    private boolean cashEnabled = true;

    private boolean upiEnabled;

    private boolean cardEnabled;

    private boolean walletEnabled;

    @Size(max = 100)
    private String upiId;

    @Size(max = 100)
    private String merchantName;

    /** One of our own uploaded photo addresses (from POST /api/v1/images), or null for none. */
    @Size(max = 255)
    private String upiQrUrl;
}