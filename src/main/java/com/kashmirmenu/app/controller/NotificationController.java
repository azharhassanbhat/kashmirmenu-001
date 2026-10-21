package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.MarkNotificationReadRequest;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.response.NotificationResponse;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CHEF','WAITER','CASHIER')")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * The restaurant is taken from the signed-in user, never from the request. These endpoints used
     * to accept a restaurantId query parameter with no ownership check, so any authenticated account
     * could read another restaurant's live order and payment notifications by changing one number.
     * The parameter is still accepted and ignored, so an older frontend build keeps working.
     */
    private final CurrentUserService currentUserService;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationResponse>> getAllNotifications(
            @RequestParam(name = "restaurantId", required = false) Long ignoredRestaurantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                notificationService.getAllNotifications(
                        currentUserService.getCurrentRestaurantId(), page, size));
    }

    @GetMapping("/unread")
    public ResponseEntity<List<NotificationResponse>> getUnreadNotifications(
            @RequestParam(name = "restaurantId", required = false) Long ignoredRestaurantId) {

        return ResponseEntity.ok(
                notificationService.getUnreadNotifications(
                        currentUserService.getCurrentRestaurantId()));
    }

    @GetMapping("/unread/count")
    public ResponseEntity<Long> getUnreadCount(
            @RequestParam(name = "restaurantId", required = false) Long ignoredRestaurantId) {

        return ResponseEntity.ok(
                notificationService.getUnreadCount(
                        currentUserService.getCurrentRestaurantId()));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<List<NotificationResponse>> getOrderNotifications(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(
                notificationService.getOrderNotifications(orderId));
    }

    @PutMapping("/{notificationId}/read")
    public ResponseEntity<NotificationResponse> markAsRead(
            @PathVariable Long notificationId,
            @Valid @RequestBody MarkNotificationReadRequest request) {

        return ResponseEntity.ok(
                notificationService.markAsRead(notificationId, request));
    }
}