package com.kashmirmenu.app.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.kashmirmenu.app.common.response.ApiResponse;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.GoogleReviewsSettingsRequest;
import com.kashmirmenu.app.dto.request.RestaurantRequest;
import com.kashmirmenu.app.dto.request.RestaurantSearchRequest;
import com.kashmirmenu.app.dto.response.RestaurantResponse;
import com.kashmirmenu.app.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
@Tag(
        name = "Restaurant Management",
        description = "APIs for managing restaurants"
)
public class RestaurantController {

    private final RestaurantService restaurantService;

    @Operation(
            summary = "Create Restaurant",
            description = "Creates a new restaurant."
    )
    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER')")
    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantResponse>> createRestaurant(
            @Valid @RequestBody RestaurantRequest request) {

        RestaurantResponse response = restaurantService.createRestaurant(request);

        ApiResponse<RestaurantResponse> apiResponse = ApiResponse.<RestaurantResponse>builder()
                .success(true)
                .message("Restaurant created successfully.")
                .data(response)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(apiResponse);
    }

    @Operation(
            summary = "Get All Restaurants",
            description = "Returns paginated restaurants."
    )

    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER','RESTAURANT_OWNER')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<RestaurantResponse>>> getAllRestaurants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {

        PageResponse<RestaurantResponse> response =
                restaurantService.getAllRestaurants(page, size, sortBy, direction);

        ApiResponse<PageResponse<RestaurantResponse>> apiResponse =
                ApiResponse.<PageResponse<RestaurantResponse>>builder()
                        .success(true)
                        .message("Restaurants fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }


    @Operation(
            summary = "Get Restaurant By Id",
            description = "Returns a restaurant by its ID."
    )
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER','RESTAURANT_OWNER')")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantResponse>> getRestaurantById(
            @PathVariable Long id) {

        RestaurantResponse response = restaurantService.getRestaurantById(id);

        ApiResponse<RestaurantResponse> apiResponse =
                ApiResponse.<RestaurantResponse>builder()
                        .success(true)
                        .message("Restaurant fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @Operation(
            summary = "Update Restaurant",
            description = "Updates restaurant details."
    )

    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER')")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantResponse>> updateRestaurant(
            @PathVariable Long id,
            @Valid @RequestBody RestaurantRequest request) {

        RestaurantResponse response =
                restaurantService.updateRestaurant(id, request);

        ApiResponse<RestaurantResponse> apiResponse =
                ApiResponse.<RestaurantResponse>builder()
                        .success(true)
                        .message("Restaurant updated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @Operation(
            summary = "Set Google reviews Place ID",
            description = "Sets or clears the Google Place ID used for review links and ratings."
    )
    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER')")
    @PutMapping("/{id}/google-reviews")
    public ResponseEntity<ApiResponse<RestaurantResponse>> updateGoogleReviews(
            @PathVariable Long id,
            @Valid @RequestBody GoogleReviewsSettingsRequest request) {

        RestaurantResponse response =
                restaurantService.updateGooglePlaceId(id, request.googlePlaceId());

        return ResponseEntity.ok(ApiResponse.<RestaurantResponse>builder()
                .success(true)
                .message("Google reviews settings saved.")
                .data(response)
                .build());
    }

    @Operation(
            summary = "Deactivate Restaurant",
            description = "Deactivates a restaurant."
    )
    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER')")
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<RestaurantResponse>> deactivateRestaurant(
            @PathVariable Long id) {

        RestaurantResponse response =
                restaurantService.deactivateRestaurant(id);

        ApiResponse<RestaurantResponse> apiResponse =
                ApiResponse.<RestaurantResponse>builder()
                        .success(true)
                        .message("Restaurant deactivated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @Operation(
            summary = "Activate Restaurant",
            description = "Activates a restaurant."
    )
    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER')")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<RestaurantResponse>> activateRestaurant(
            @PathVariable Long id) {

        RestaurantResponse response =
                restaurantService.activateRestaurant(id);

        ApiResponse<RestaurantResponse> apiResponse =
                ApiResponse.<RestaurantResponse>builder()
                        .success(true)
                        .message("Restaurant activated successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }

    @Operation(
            summary = "Search Restaurants",
            description = "Search restaurants using filters like name, city, active status, pagination and sorting."
    )
    @Parameters({
            @Parameter(name = "name", description = "Restaurant name"),
            @Parameter(name = "city", description = "Restaurant city"),
            @Parameter(name = "active", description = "Restaurant active status"),
            @Parameter(name = "page", description = "Page number"),
            @Parameter(name = "size", description = "Page size"),
            @Parameter(name = "sortBy", description = "Field to sort by"),
            @Parameter(name = "direction", description = "Sort direction (asc or desc)")
    })
    @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER','RESTAURANT_OWNER')")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<RestaurantResponse>>> searchRestaurants(
             @Valid @ModelAttribute RestaurantSearchRequest request) {

        PageResponse<RestaurantResponse> response =
                restaurantService.searchRestaurants(request);

        ApiResponse<PageResponse<RestaurantResponse>> apiResponse =
                ApiResponse.<PageResponse<RestaurantResponse>>builder()
                        .success(true)
                        .message("Restaurants fetched successfully.")
                        .data(response)
                        .build();

        return ResponseEntity.ok(apiResponse);
    }
}