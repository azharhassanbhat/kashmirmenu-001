package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.response.NotificationResponse;
import com.kashmirmenu.app.entity.Notification;
import com.kashmirmenu.app.enums.NotificationEventKind;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    @Mapping(target = "restaurantId", source = "restaurant.id")
    @Mapping(target = "restaurantName", source = "restaurant.name")
    @Mapping(target = "orderId", source = "order.id")
    @Mapping(target = "orderNumber", source = "order.orderNumber")
    @Mapping(target = "eventKind", expression = "java(notification.getEventKind() != null ? notification.getEventKind() : com.kashmirmenu.app.enums.NotificationEventKind.defaultFor(notification.getNotificationType()))")
    NotificationResponse toResponse(Notification notification);
}