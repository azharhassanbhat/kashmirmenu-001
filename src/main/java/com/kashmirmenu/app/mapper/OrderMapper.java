package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.entity.OrderItemOption;
import com.kashmirmenu.app.dto.response.SelectedOptionResponse;
import com.kashmirmenu.app.dto.response.OrderItemResponse;
import com.kashmirmenu.app.dto.response.OrderResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    // Payment state is set by OrderServiceImpl from one batched lookup for the whole page, not
    // derived per entity - deriving it here would mean a query per row, which is the N+1 the
    // paged listing exists to avoid.
    @Mapping(target = "restaurantId", source = "restaurant.id")
    @Mapping(target = "restaurantTableId", source = "restaurantTable.id")
    @Mapping(target = "dinerSessionId", source = "dinerSession.id")
    @Mapping(target = "paymentStatus", ignore = true)
    @Mapping(target = "amountPaid", ignore = true)
    @Mapping(target = "balanceDue", ignore = true)
    OrderResponse toResponse(Order order);

    List<OrderResponse> toOrderResponseList(List<Order> orders);

    @Mapping(target = "menuItemId", source = "menuItem.id")
    OrderItemResponse toResponse(OrderItem orderItem);

    List<OrderItemResponse> toOrderItemResponseList(List<OrderItem> orderItems);

    /** A choice as copied onto the order line. */
    SelectedOptionResponse toOption(OrderItemOption option);
}