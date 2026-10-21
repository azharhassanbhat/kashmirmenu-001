package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.response.KitchenOrderResponse;
import com.kashmirmenu.app.service.KitchenService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CHEF','WAITER')")
@RequestMapping("/api/v1/kitchen/orders")
@RequiredArgsConstructor
public class KitchenController {

    private final KitchenService kitchenService;

    @Operation(summary = "Get all pending kitchen orders")
    @GetMapping("/pending")
    public ResponseEntity<List<KitchenOrderResponse>> getPendingOrders() {

        return ResponseEntity.ok(
                kitchenService.getPendingOrders());
    }

    @Operation(summary = "Get all in-progress kitchen orders (pending, confirmed, preparing, ready)")
    @GetMapping("/active")
    public ResponseEntity<List<KitchenOrderResponse>> getActiveOrders() {

        return ResponseEntity.ok(
                kitchenService.getActiveOrders());
    }

    @Operation(summary = "Today's orders in every status, newest first")
    @GetMapping("/today")
    public ResponseEntity<List<KitchenOrderResponse>> getTodaysOrders() {

        return ResponseEntity.ok(
                kitchenService.getTodaysOrders());
    }

    @Operation(summary = "Confirm an order")
    @PutMapping("/{orderId}/confirm")
    public ResponseEntity<KitchenOrderResponse> confirmOrder(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(
                kitchenService.acceptOrder(orderId));
    }

    @Operation(summary = "Start preparing an order")
    @PutMapping("/{orderId}/preparing")
    public ResponseEntity<KitchenOrderResponse> startPreparing(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(
                kitchenService.startPreparing(orderId));
    }

    @Operation(summary = "Mark order as ready")
    @PutMapping("/{orderId}/ready")
    public ResponseEntity<KitchenOrderResponse> markReady(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(
                kitchenService.markReady(orderId));
    }

    @Operation(summary = "Mark order as served")
    @PutMapping("/{orderId}/served")
    public ResponseEntity<KitchenOrderResponse> markServed(
            @PathVariable Long orderId) {

        return ResponseEntity.ok(
                kitchenService.markServed(orderId));
    }
}