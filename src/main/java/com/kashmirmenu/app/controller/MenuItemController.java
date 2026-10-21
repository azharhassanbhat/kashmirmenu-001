package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.common.response.ApiResponse;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.MenuItemRequest;
import com.kashmirmenu.app.dto.response.MenuItemResponse;
import com.kashmirmenu.app.service.MenuItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/menu-items")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class MenuItemController {

    private final MenuItemService menuItemService;

    @PostMapping
    public ResponseEntity<ApiResponse<MenuItemResponse>> createMenuItem(
            @RequestBody @Valid MenuItemRequest request) {

        MenuItemResponse response = menuItemService.createMenuItem(request);

        ApiResponse<MenuItemResponse> apiResponse =
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item created successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MenuItemResponse>>> getAllMenuItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {

        PageResponse<MenuItemResponse> response =
                menuItemService.getAllMenuItems(page, size, sortBy, direction);

        ApiResponse<PageResponse<MenuItemResponse>> apiResponse =
                ApiResponse.<PageResponse<MenuItemResponse>>builder()
                        .success(true)
                        .message("Menu items fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MenuItemResponse>> getMenuItemById(
            @PathVariable Long id) {

        MenuItemResponse response = menuItemService.getMenuItemById(id);

        ApiResponse<MenuItemResponse> apiResponse =
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MenuItemResponse>> updateMenuItem(
            @PathVariable Long id,
            @RequestBody @Valid MenuItemRequest request) {

        MenuItemResponse response =
                menuItemService.updateMenuItem(id, request);

        ApiResponse<MenuItemResponse> apiResponse =
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item updated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<MenuItemResponse>> activateMenuItem(
            @PathVariable Long id) {

        MenuItemResponse response = menuItemService.activateMenuItem(id);

        return ResponseEntity.ok(
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item activated successfully.")
                        .data(response)
                        .build());
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<MenuItemResponse>> deactivateMenuItem(
            @PathVariable Long id) {

        MenuItemResponse response = menuItemService.deactivateMenuItem(id);

        return ResponseEntity.ok(
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item deactivated successfully.")
                        .data(response)
                        .build());
    }

    @PatchMapping("/{id}/available")
    public ResponseEntity<ApiResponse<MenuItemResponse>> markAvailable(
            @PathVariable Long id) {

        MenuItemResponse response = menuItemService.markAvailable(id);

        return ResponseEntity.ok(
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item marked as available.")
                        .data(response)
                        .build());
    }

    @PatchMapping("/{id}/unavailable")
    public ResponseEntity<ApiResponse<MenuItemResponse>> markUnavailable(
            @PathVariable Long id) {

        MenuItemResponse response = menuItemService.markUnavailable(id);

        return ResponseEntity.ok(
                ApiResponse.<MenuItemResponse>builder()
                        .success(true)
                        .message("Menu item marked as unavailable.")
                        .data(response)
                        .build());
    }
}