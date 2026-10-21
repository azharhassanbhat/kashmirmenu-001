package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.service.MenuCsv;

import java.math.BigDecimal;
import java.util.List;

/**
 * What importing a file would do, without doing it. When {@code problems} is not empty nothing can be
 * imported and {@code rows} is empty: fix the file and preview again.
 */
public record MenuImportPreviewResponse(
        boolean canImport,
        List<Row> rows,
        List<MenuCsv.Problem> problems,
        List<String> newCategories,
        int toCreate,
        int alreadyExist) {

    /** @param status NEW (will be created) or EXISTS (the same dish is already in that category: skipped) */
    public record Row(int line, String category, String name, BigDecimal price, boolean veg,
                      boolean available, int prepMinutes, String status, boolean newCategory) {
    }
}
