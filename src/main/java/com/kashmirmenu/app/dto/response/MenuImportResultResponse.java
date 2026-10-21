package com.kashmirmenu.app.dto.response;

/** What an import did. Dishes that already existed in their category were left exactly as they were. */
public record MenuImportResultResponse(int created, int skippedExisting, int categoriesCreated) {
}
