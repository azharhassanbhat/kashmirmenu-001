package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.CreateOrderRequest;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.response.OrderResponse;
import com.kashmirmenu.app.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CHEF','WAITER','CASHIER')")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final com.kashmirmenu.app.service.ReceiptService receiptService;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @Valid @RequestBody CreateOrderRequest request) {

        return ResponseEntity.ok(
                orderService.placeOrder(request)
        );
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(
                orderService.getOrder(orderId)
        );
    }

    /**
     * BREAKING CHANGE: returns a page, not a bare array. It previously returned every order the
     * restaurant had ever taken, with a follow-up query per order for its items.
     */
    @GetMapping
    public ResponseEntity<PageResponse<OrderResponse>> getOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                orderService.getOrders(page, size)
        );
    }

    /** BREAKING: returns a page. Previously every order in that status, with an N+1 on items. */
    @GetMapping("/status/{status}")
    public ResponseEntity<PageResponse<OrderResponse>> getOrdersByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(
                orderService.getOrdersByStatus(status, page, size)
        );
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestParam String status,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String note) {

        return ResponseEntity.ok(
                orderService.updateOrderStatus(
                        orderId,
                        status,
                        reason,
                        note
                )
        );
    }

    @GetMapping("/{orderId}/receipt")
    public ResponseEntity<com.kashmirmenu.app.dto.response.ReceiptResponse> getReceipt(@PathVariable Long orderId) {

        return ResponseEntity.ok(receiptService.forOrderId(orderId));
    }

    @GetMapping("/{orderId}/history")
    public ResponseEntity<java.util.List<com.kashmirmenu.app.dto.response.OrderHistoryEntryResponse>> getOrderHistory(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(orderService.getOrderHistory(orderId));
    }
}