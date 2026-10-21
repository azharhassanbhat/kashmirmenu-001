package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.CreateRestaurantTableRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantTableRequest;
import com.kashmirmenu.app.dto.response.RestaurantTableResponse;
import com.kashmirmenu.app.entity.RestaurantTable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface RestaurantTableMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    @Mapping(target = "qrIdentifier", ignore = true)
    @Mapping(target = "active", ignore = true)
    RestaurantTable toEntity(CreateRestaurantTableRequest request);

    RestaurantTableResponse toResponse(RestaurantTable entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    @Mapping(target = "qrIdentifier", ignore = true)
    @Mapping(target = "active", ignore = true)
    void updateEntity(UpdateRestaurantTableRequest request,
                      @MappingTarget RestaurantTable entity);
}