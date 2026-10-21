package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.concurrency.LockRetry;
import com.kashmirmenu.app.dto.request.UpdateSettlementStatusRequest;
import com.kashmirmenu.app.dto.response.SettlementStaffResponse;
import com.kashmirmenu.app.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Staff side of QR payments: see what guests have started paying, and confirm that the money arrived. */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CASHIER','WAITER')")
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlementService;
    private final LockRetry retry;

    @Operation(summary = "Settlements with a payment still waiting for staff to confirm")
    @GetMapping("/pending")
    public List<SettlementStaffResponse> pending() {
        return settlementService.awaitingStaff();
    }

    @Operation(summary = "Confirm (SUCCESS) or reject (FAILED / CANCELLED) every pending payment in a settlement")
    @PutMapping("/{settlementId}/status")
    public SettlementStaffResponse updateStatus(
            @PathVariable Long settlementId,
            @Valid @RequestBody UpdateSettlementStatusRequest request) {
        return retry.call("settlement-confirm", () -> settlementService.updateStatus(settlementId, request));
    }
}
