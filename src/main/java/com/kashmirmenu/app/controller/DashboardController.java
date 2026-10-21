package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.response.DashboardSummaryResponse;
import com.kashmirmenu.app.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getDashboardSummary() {

        DashboardSummaryResponse response =
                dashboardService.getDashboardSummary();

        return ResponseEntity.ok(response);
    }
}