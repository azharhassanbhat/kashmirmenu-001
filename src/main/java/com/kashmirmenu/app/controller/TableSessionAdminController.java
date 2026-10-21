package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.concurrency.LockRetry;
import com.kashmirmenu.app.dto.response.TableSessionDetailResponse;
import com.kashmirmenu.app.dto.response.TableSessionSummaryResponse;
import com.kashmirmenu.app.service.TableSessionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The owner's live view of the floor: open tables, who is at them, what they owe, closing and reopening. */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER','CASHIER','WAITER')")
@RequestMapping("/api/v1/table-sessions")
@RequiredArgsConstructor
public class TableSessionAdminController {

    private final TableSessionAdminService service;
    private final LockRetry retry;

    @Operation(summary = "Open tables, each with its guest count and what is owed")
    @GetMapping("/active")
    public List<TableSessionSummaryResponse> active() {
        return service.active();
    }

    @Operation(summary = "Tables closed in the last day, newest first, so one closed by mistake can be reopened")
    @GetMapping("/recent")
    public List<TableSessionSummaryResponse> recentlyClosed() {
        return service.recentlyClosed();
    }

    @Operation(summary = "One table's guests, their orders and balances, and its payment history")
    @GetMapping("/{id}")
    public TableSessionDetailResponse detail(@PathVariable Long id) {
        return service.detail(id);
    }

    @Operation(summary = "Close a table. Never blocked by money owing; nothing is deleted or settled")
    @PostMapping("/{id}/close")
    public TableSessionDetailResponse close(@PathVariable Long id) {
        return retry.call("table-close", () -> service.close(id));
    }

    @Operation(summary = "Reopen a table that was closed, unless it has since started a new session")
    @PostMapping("/{id}/reopen")
    public TableSessionDetailResponse reopen(@PathVariable Long id) {
        return retry.call("table-reopen", () -> service.reopen(id));
    }
}
