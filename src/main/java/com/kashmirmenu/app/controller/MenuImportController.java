package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.request.MenuImportRequest;
import com.kashmirmenu.app.dto.response.MenuImportPreviewResponse;
import com.kashmirmenu.app.dto.response.MenuImportResultResponse;
import com.kashmirmenu.app.service.MenuImportService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Loading a menu from a CSV file. Management only; preview first, then import. */
@RestController
@RequestMapping("/api/menu-items/import")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class MenuImportController {

    private final MenuImportService menuImportService;

    @Operation(summary = "What importing this CSV would do (changes nothing)")
    @PostMapping("/preview")
    public MenuImportPreviewResponse preview(@Valid @RequestBody MenuImportRequest request) {
        return menuImportService.preview(request.csv());
    }

    @Operation(summary = "Import the CSV: all or nothing; dishes that already exist are skipped")
    @PostMapping
    public MenuImportResultResponse importMenu(@Valid @RequestBody MenuImportRequest request) {
        return menuImportService.importMenu(request.csv());
    }
}
