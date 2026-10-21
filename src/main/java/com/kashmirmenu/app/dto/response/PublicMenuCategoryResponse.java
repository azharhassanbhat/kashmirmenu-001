package com.kashmirmenu.app.dto.response;

import java.util.List;

public record PublicMenuCategoryResponse(
        Long id,
        String name,
        String description,
        List<MenuItemResponse> items) {
}
