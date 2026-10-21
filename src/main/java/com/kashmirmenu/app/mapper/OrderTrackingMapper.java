package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.entity.OrderItemOption;
import com.kashmirmenu.app.dto.response.SelectedOptionResponse;
import com.kashmirmenu.app.dto.response.OrderTrackingItemResponse;
import com.kashmirmenu.app.dto.response.OrderTrackingResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderTrackingMapper {

    @Mapping(target = "tableName",
            source = "restaurantTable.tableName")
    // Set by OrderTrackingServiceImpl from the order's payment balance.
    // Set by OrderTrackingServiceImpl: the label of the staff-chosen reason, never the note.
    @Mapping(target = "feedbackRating",
            ignore = true)
    @Mapping(target = "feedbackComment",
            ignore = true)
    @Mapping(target = "cancelReasonLabel",
            ignore = true)
    @Mapping(target = "paymentStatus",
            ignore = true)
    @Mapping(target = "amountPaid",
            ignore = true)
    @Mapping(target = "balanceDue",
            ignore = true)
    @Mapping(target = "items",
            source = "items")
    OrderTrackingResponse toResponse(Order order);

    @Mapping(target = "itemName",
            source = "itemName")
    @Mapping(target = "quantity",
            source = "quantity")
    @Mapping(target = "unitPrice",
            source = "unitPrice")
    @Mapping(target = "lineTotal",
            source = "lineTotal")
    OrderTrackingItemResponse toItemResponse(OrderItem orderItem);

    SelectedOptionResponse toOption(OrderItemOption option);
}