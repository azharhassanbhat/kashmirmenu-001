package com.kashmirmenu.app.service;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.MenuItemRequest;
import com.kashmirmenu.app.dto.response.MenuItemResponse;

public interface MenuItemService {

    MenuItemResponse createMenuItem(MenuItemRequest request);

    PageResponse<MenuItemResponse> getAllMenuItems(
            int page,
            int size,
            String sortBy,
            String direction);

    MenuItemResponse getMenuItemById(Long id);

    MenuItemResponse updateMenuItem(Long id, MenuItemRequest request);

    MenuItemResponse activateMenuItem(Long id);

    MenuItemResponse deactivateMenuItem(Long id);

    MenuItemResponse markAvailable(Long id);

    MenuItemResponse markUnavailable(Long id);
}