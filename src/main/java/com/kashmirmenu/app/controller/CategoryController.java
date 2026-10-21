package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.common.response.ApiResponse;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.CategoryRequest;
import com.kashmirmenu.app.dto.response.CategoryResponse;
import com.kashmirmenu.app.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> createCategory(
            @RequestBody CategoryRequest request) {

        CategoryResponse response = categoryService.createCategory(request);

        ApiResponse<CategoryResponse> apiResponse =
                ApiResponse.<CategoryResponse>builder()
                        .success(true)
                        .message("Category created successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CategoryResponse>>> getAllCategories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {

        PageResponse<CategoryResponse> response =
                categoryService.getAllCategories(page, size, sortBy, direction);

        ApiResponse<PageResponse<CategoryResponse>> apiResponse =
                ApiResponse.<PageResponse<CategoryResponse>>builder()
                        .success(true)
                        .message("Categories fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> getCategoryById(
            @PathVariable Long id) {

        CategoryResponse response = categoryService.getCategoryById(id);

        ApiResponse<CategoryResponse> apiResponse =
                ApiResponse.<CategoryResponse>builder()
                        .success(true)
                        .message("Category fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryResponse>> updateCategory(
            @PathVariable Long id,
            @RequestBody @Valid CategoryRequest request) {

        CategoryResponse response =
                categoryService.updateCategory(id, request);

        ApiResponse<CategoryResponse> apiResponse =
                ApiResponse.<CategoryResponse>builder()
                        .success(true)
                        .message("Category updated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<CategoryResponse>> activateCategory(
            @PathVariable Long id) {

        CategoryResponse response =
                categoryService.activateCategory(id);

        ApiResponse<CategoryResponse> apiResponse =
                ApiResponse.<CategoryResponse>builder()
                        .success(true)
                        .message("Category activated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<CategoryResponse>> deactivateCategory(
            @PathVariable Long id) {

        CategoryResponse response =
                categoryService.deactivateCategory(id);

        ApiResponse<CategoryResponse> apiResponse =
                ApiResponse.<CategoryResponse>builder()
                        .success(true)
                        .message("Category deactivated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }
}