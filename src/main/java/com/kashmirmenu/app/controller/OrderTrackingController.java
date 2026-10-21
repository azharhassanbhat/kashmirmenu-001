package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.common.response.ApiResponse;
import com.kashmirmenu.app.dto.response.OrderTrackingResponse;
import com.kashmirmenu.app.service.OrderTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CHEF','WAITER','CASHIER')")
@RequiredArgsConstructor
@Tag(name = "Order Tracking", description = "APIs for tracking customer orders")
public class OrderTrackingController {

    private final OrderTrackingService orderTrackingService;

    @GetMapping("/{orderNumber}/tracking")
    @Operation(summary = "Track an order by order number")
    public ApiResponse<OrderTrackingResponse> trackOrder(
            @PathVariable String orderNumber) {

        OrderTrackingResponse response =
                orderTrackingService.trackOrder(orderNumber);

        return ApiResponse.<OrderTrackingResponse>builder()
                .success(true)
                .message("Order tracking fetched successfully.")
                .data(response)
                .build();
    }
}