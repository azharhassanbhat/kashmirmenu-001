package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.CreateCouponRequest;
import com.kashmirmenu.app.dto.request.UpdateCouponRequest;
import com.kashmirmenu.app.dto.response.CouponResponse;
import com.kashmirmenu.app.entity.Coupon;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface CouponMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    @Mapping(target = "usedCount", ignore = true)
    Coupon toEntity(CreateCouponRequest request);

    @BeanMapping(
            nullValuePropertyMappingStrategy =
                    NullValuePropertyMappingStrategy.IGNORE
    )
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "usedCount", ignore = true)
    void updateEntity(
            UpdateCouponRequest request,
            @MappingTarget Coupon coupon
    );

    @Mapping(target = "restaurantId", source = "restaurant.id")
    @Mapping(target = "restaurantName", source = "restaurant.name")
    CouponResponse toResponse(Coupon coupon);
}