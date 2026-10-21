package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.CreateStaffRequest;
import com.kashmirmenu.app.dto.request.ResetStaffPasswordRequest;
import com.kashmirmenu.app.dto.request.UpdateStaffRequest;
import com.kashmirmenu.app.dto.response.StaffResponse;
import com.kashmirmenu.app.service.StaffService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The restaurant's team. Owners, restaurant admins and managers reach this controller; which accounts
 * each may create or change is decided in {@code StaffService} (a manager cannot touch other managers).
 */
@RestController
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;

    @Operation(summary = "This restaurant's team, with what the caller may do with each account")
    @GetMapping
    public List<StaffResponse> list() {
        return staffService.list();
    }

    @Operation(summary = "Create a staff account (manager, chef, waiter or cashier)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffResponse create(@Valid @RequestBody CreateStaffRequest request) {
        return staffService.create(request);
    }

    @Operation(summary = "Rename, change the role of, or switch off/on a staff account")
    @PutMapping("/{id}")
    public StaffResponse update(@PathVariable Long id, @Valid @RequestBody UpdateStaffRequest request) {
        return staffService.update(id, request);
    }

    @Operation(summary = "Set a new password for a staff member; they are signed out everywhere")
    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetStaffPasswordRequest request) {
        staffService.resetPassword(id, request);
    }
}
