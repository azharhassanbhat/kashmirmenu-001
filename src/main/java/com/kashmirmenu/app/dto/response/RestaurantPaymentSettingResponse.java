package com.kashmirmenu.app.dto.response;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RestaurantPaymentSettingResponse {

    private Long id;

    private Long restaurantId;

    private boolean cashEnabled;

    private boolean upiEnabled;

    private boolean cardEnabled;

    private boolean walletEnabled;

    private String upiId;

    private String merchantName;

    private String upiQrUrl;
}