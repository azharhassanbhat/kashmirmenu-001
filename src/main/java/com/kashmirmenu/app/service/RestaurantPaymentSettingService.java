package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.CreateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.response.RestaurantPaymentSettingResponse;

public interface RestaurantPaymentSettingService {

    RestaurantPaymentSettingResponse createPaymentSetting(
            Long restaurantId,
            CreateRestaurantPaymentSettingRequest request
    );

    RestaurantPaymentSettingResponse getPaymentSetting(
            Long restaurantId
    );

    RestaurantPaymentSettingResponse updatePaymentSetting(
            Long restaurantId,
            UpdateRestaurantPaymentSettingRequest request
    );
}