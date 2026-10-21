package com.kashmirmenu.app.platform;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.DecisionRequest;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.Detail;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.Overview;
import com.kashmirmenu.app.platform.PlatformRestaurantDtos.Summary;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration approvals and restaurant lifecycle, for the platform owner only. The filter chain
 * already restricts /api/platform-admin/** to PLATFORM_OWNER; the class-level annotation repeats
 * the rule so the controller stays safe if the chain is ever reshaped.
 */
@RestController
@RequestMapping("/api/platform-admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_OWNER')")
public class PlatformRestaurantController {

    private final PlatformRestaurantService service;

    @Operation(summary = "Counts of restaurants by approval status")
    @GetMapping("/overview")
    public Overview overview() {
        return service.overview();
    }

    @Operation(summary = "Search registered restaurants")
    @GetMapping("/restaurants")
    public PageResponse<Summary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) RestaurantStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        return service.search(q, status, page, size, sortBy, direction);
    }

    @Operation(summary = "Everything the platform owner needs to decide on one restaurant")
    @GetMapping("/restaurants/{id}")
    public Detail detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @Operation(summary = "Approve a pending (or previously rejected) restaurant")
    @PostMapping("/restaurants/{id}/approve")
    public Detail approve(@PathVariable Long id) {
        return service.approve(id);
    }

    @Operation(summary = "Reject a pending restaurant; a reason is required")
    @PostMapping("/restaurants/{id}/reject")
    public Detail reject(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.reject(id, request.reason());
    }

    @Operation(summary = "Suspend an approved restaurant; a reason is required")
    @PostMapping("/restaurants/{id}/suspend")
    public Detail suspend(@PathVariable Long id, @Valid @RequestBody DecisionRequest request) {
        return service.suspend(id, request.reason());
    }

    @Operation(summary = "Reactivate a suspended restaurant")
    @PostMapping("/restaurants/{id}/reactivate")
    public Detail reactivate(@PathVariable Long id) {
        return service.reactivate(id);
    }
}
