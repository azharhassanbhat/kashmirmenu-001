package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.common.response.ApiResponse;
import com.kashmirmenu.app.dto.request.CreateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateStockRequest;
import com.kashmirmenu.app.dto.response.InventoryResponse;
import com.kashmirmenu.app.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
@Tag(name = "Inventory", description = "Inventory Management APIs")
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    @Operation(summary = "Create inventory")
    public ApiResponse<InventoryResponse> createInventory(
            @Valid @RequestBody CreateInventoryRequest request) {

        InventoryResponse response =
                inventoryService.createInventory(request);

        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory created successfully.")
                .data(response)
                .build();
    }

    @GetMapping
    @Operation(summary = "Get all inventory")
    public ApiResponse<List<InventoryResponse>> getAllInventory() {

        List<InventoryResponse> response =
                inventoryService.getAllInventory();

        return ApiResponse.<List<InventoryResponse>>builder()
                .success(true)
                .message("Inventory fetched successfully.")
                .data(response)
                .build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get inventory by id")
    public ApiResponse<InventoryResponse> getInventoryById(
            @PathVariable Long id) {

        InventoryResponse response =
                inventoryService.getInventoryById(id);

        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory fetched successfully.")
                .data(response)
                .build();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update inventory")
    public ApiResponse<InventoryResponse> updateInventory(
            @PathVariable Long id,
            @Valid @RequestBody UpdateInventoryRequest request) {

        InventoryResponse response =
                inventoryService.updateInventory(id, request);

        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Inventory updated successfully.")
                .data(response)
                .build();
    }

    @PatchMapping("/{id}/stock")
    @Operation(summary = "Update stock quantity")
    public ApiResponse<InventoryResponse> updateStock(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStockRequest request) {

        InventoryResponse response =
                inventoryService.updateStock(id, request);

        return ApiResponse.<InventoryResponse>builder()
                .success(true)
                .message("Stock updated successfully.")
                .data(response)
                .build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete inventory")
    public ApiResponse<Void> deleteInventory(
            @PathVariable Long id) {

        inventoryService.deleteInventory(id);

        return ApiResponse.<Void>builder()
                .success(true)
                .message("Inventory deleted successfully.")
                .build();
    }
}