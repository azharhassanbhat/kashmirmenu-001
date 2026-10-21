package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.CreateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateInventoryRequest;
import com.kashmirmenu.app.dto.response.InventoryResponse;
import com.kashmirmenu.app.entity.Inventory;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface InventoryMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    @Mapping(target = "menuItem", ignore = true)
    Inventory toEntity(CreateInventoryRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "restaurant", ignore = true)
    @Mapping(target = "menuItem", ignore = true)
    @Mapping(target = "availableQuantity", ignore = true)
    void updateEntity(UpdateInventoryRequest request,
                      @MappingTarget Inventory inventory);

    @Mapping(target = "restaurantId", source = "restaurant.id")
    @Mapping(target = "restaurantName", source = "restaurant.name")
    @Mapping(target = "menuItemId", source = "menuItem.id")
    @Mapping(target = "menuItemName", source = "menuItem.name")
    InventoryResponse toResponse(Inventory inventory);
}