package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.response.KitchenOrderResponse;
import com.kashmirmenu.app.entity.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = OrderMapper.class)
public interface KitchenMapper {

    @Mapping(source = "id", target = "orderId")
    @Mapping(source = "restaurantTable.id", target = "restaurantTableId")
    @Mapping(source = "restaurantTable.tableName", target = "tableName")
    @Mapping(source = "createdAt", target = "createdAt")
    // Timing is worked out by KitchenServiceImpl from the server clock and the dishes' prep times.
    @Mapping(target = "prepTargetMinutes", ignore = true)
    @Mapping(target = "elapsedMinutes", ignore = true)
    @Mapping(target = "delayed", ignore = true)
    @Mapping(target = "overdueMinutes", ignore = true)
    KitchenOrderResponse toResponse(Order order);
}