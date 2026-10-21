package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.CreateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.response.RestaurantPaymentSettingResponse;
import com.kashmirmenu.app.entity.RestaurantPaymentSetting;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface RestaurantPaymentSettingMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    RestaurantPaymentSetting toEntity(CreateRestaurantPaymentSettingRequest request);

    @Mapping(target = "restaurantId", source = "restaurant.id")
    RestaurantPaymentSettingResponse toResponse(RestaurantPaymentSetting entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    void updateEntity(UpdateRestaurantPaymentSettingRequest request,
                      @MappingTarget RestaurantPaymentSetting entity);
}