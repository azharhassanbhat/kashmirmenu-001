package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.UpdateServiceRequestStatusRequest;
import com.kashmirmenu.app.dto.response.ServiceRequestResponse;
import com.kashmirmenu.app.service.ServiceRequestService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The staff side of service requests. Floor and kitchen staff, like the orders they already work;
 * every lookup is scoped to the caller's own restaurant inside the service.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CHEF','WAITER','CASHIER')")
@RequestMapping("/api/v1/service-requests")
@RequiredArgsConstructor
public class ServiceRequestController {

    private final ServiceRequestService serviceRequestService;

    @Operation(summary = "The staff board: open requests oldest first, plus anything finished in the last two hours")
    @GetMapping
    public List<ServiceRequestResponse> board() {
        return serviceRequestService.board();
    }

    @Operation(summary = "One request with its full status history")
    @GetMapping("/{id}")
    public ServiceRequestResponse get(@PathVariable Long id) {
        return serviceRequestService.get(id);
    }

    @Operation(summary = "Move a request along PENDING -> ACKNOWLEDGED -> IN_PROGRESS -> COMPLETED, or cancel it")
    @PutMapping("/{id}/status")
    public ServiceRequestResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateServiceRequestStatusRequest request) {
        return serviceRequestService.updateStatus(id, request.status());
    }
}
