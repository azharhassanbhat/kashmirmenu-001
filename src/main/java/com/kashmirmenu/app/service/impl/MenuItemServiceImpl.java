package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.MenuItemRequest;
import com.kashmirmenu.app.dto.response.MenuItemResponse;
import com.kashmirmenu.app.entity.Category;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.exception.DuplicateResourceException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.MenuItemMapper;
import com.kashmirmenu.app.repository.CategoryRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.MenuItemService;
import com.kashmirmenu.app.util.PageResponseUtils;
import com.kashmirmenu.app.util.PageableUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MenuItemServiceImpl implements MenuItemService {

    private final MenuItemRepository menuItemRepository;
    private final CategoryRepository categoryRepository;
    private final MenuItemMapper menuItemMapper;
    private final CurrentUserService currentUserService;

    @Override
    public MenuItemResponse createMenuItem(
            MenuItemRequest request) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        log.info(
                "Creating menu item '{}' for restaurant {}",
                request.getName(),
                restaurantId
        );

        Category category =
                getCategory(
                        request.getCategoryId(),
                        restaurantId
                );

        if (menuItemRepository
                .existsByRestaurantIdAndCategoryIdAndName(
                        restaurantId,
                        request.getCategoryId(),
                        request.getName())) {

            log.warn(
                    "Menu item '{}' already exists in category {}",
                    request.getName(),
                    request.getCategoryId()
            );

            throw new DuplicateResourceException(
                    "Menu item already exists in this category."
            );
        }

        MenuItem menuItem =
                menuItemMapper.toEntity(request);

        menuItem.setRestaurant(restaurant);
        menuItem.setCategory(category);

        MenuItemResponse response =
                saveMenuItem(menuItem);

        log.info(
                "Menu item '{}' created successfully.",
                response.getName()
        );

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MenuItemResponse> getAllMenuItems(
            int page,
            int size,
            String sortBy,
            String direction) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        Pageable pageable =
                PageableUtils.createPageable(
                        page,
                        size,
                        sortBy,
                        direction,
                        PageableUtils.MENU_ITEM_SORT
                );

        Page<MenuItem> menuItemPage =
                menuItemRepository.findByRestaurantId(
                        restaurantId,
                        pageable
                );

        return PageResponseUtils.fromPage(
                menuItemPage,
                menuItemMapper::toResponse
        );
    }

    @Override
    @Transactional(readOnly = true)
    public MenuItemResponse getMenuItemById(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        MenuItem menuItem =
                getMenuItem(id, restaurantId);

        return menuItemMapper.toResponse(menuItem);
    }

    @Override
    public MenuItemResponse updateMenuItem(
            Long id,
            MenuItemRequest request) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        log.info(
                "Updating menu item {} for restaurant {}",
                id,
                restaurantId
        );

        MenuItem menuItem =
                getMenuItem(id, restaurantId);

        Category category =
                getCategory(
                        request.getCategoryId(),
                        restaurantId
                );

        if (!menuItem.getName().equalsIgnoreCase(
                request.getName())
                && menuItemRepository
                .existsByRestaurantIdAndCategoryIdAndName(
                        restaurantId,
                        request.getCategoryId(),
                        request.getName())) {

            log.warn(
                    "Menu item '{}' already exists in category {}",
                    request.getName(),
                    request.getCategoryId()
            );

            throw new DuplicateResourceException(
                    "Menu item already exists in this category."
            );
        }

        menuItemMapper.updateEntity(
                menuItem,
                request
        );

        menuItem.setRestaurant(restaurant);
        menuItem.setCategory(category);

        MenuItemResponse response =
                saveMenuItem(menuItem);

        log.info(
                "Menu item {} updated successfully.",
                id
        );

        return response;
    }

    @Override
    public MenuItemResponse activateMenuItem(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Activating menu item {} for restaurant {}",
                id,
                restaurantId
        );

        MenuItem menuItem =
                getMenuItem(id, restaurantId);

        menuItem.setActive(true);

        MenuItemResponse response =
                saveMenuItem(menuItem);

        log.info(
                "Menu item {} activated successfully.",
                id
        );

        return response;
    }

    @Override
    public MenuItemResponse deactivateMenuItem(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Deactivating menu item {} for restaurant {}",
                id,
                restaurantId
        );

        MenuItem menuItem =
                getMenuItem(id, restaurantId);

        menuItem.setActive(false);

        MenuItemResponse response =
                saveMenuItem(menuItem);

        log.info(
                "Menu item {} deactivated successfully.",
                id
        );

        return response;
    }

    @Override
    public MenuItemResponse markAvailable(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Marking menu item {} as available for restaurant {}",
                id,
                restaurantId
        );

        MenuItem menuItem =
                getMenuItem(id, restaurantId);

        menuItem.setAvailable(true);

        MenuItemResponse response =
                saveMenuItem(menuItem);

        log.info(
                "Menu item {} is now available.",
                id
        );

        return response;
    }

    @Override
    public MenuItemResponse markUnavailable(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Marking menu item {} as unavailable for restaurant {}",
                id,
                restaurantId
        );

        MenuItem menuItem =
                getMenuItem(id, restaurantId);

        menuItem.setAvailable(false);

        MenuItemResponse response =
                saveMenuItem(menuItem);

        log.info(
                "Menu item {} is now unavailable.",
                id
        );

        return response;
    }

    private MenuItem getMenuItem(
            Long id,
            Long restaurantId) {

        log.debug(
                "Fetching menu item {} for restaurant {}",
                id,
                restaurantId
        );

        return menuItemRepository
                .findByIdAndRestaurantId(
                        id,
                        restaurantId
                )
                .orElseThrow(() -> {

                    log.warn(
                            "Menu item {} not found for restaurant {}",
                            id,
                            restaurantId
                    );

                    return new ResourceNotFoundException(
                            "Menu item not found."
                    );
                });
    }

    private Category getCategory(
            Long categoryId,
            Long restaurantId) {

        log.debug(
                "Fetching category {} for restaurant {}",
                categoryId,
                restaurantId
        );

        return categoryRepository
                .findByIdAndRestaurantId(
                        categoryId,
                        restaurantId
                )
                .orElseThrow(() -> {

                    log.warn(
                            "Category {} not found for restaurant {}",
                            categoryId,
                            restaurantId
                    );

                    return new ResourceNotFoundException(
                            "Category not found for this restaurant."
                    );
                });
    }

    private MenuItemResponse saveMenuItem(
            MenuItem menuItem) {

        MenuItem savedMenuItem =
                menuItemRepository.save(menuItem);

        log.debug(
                "Menu item saved. id={}",
                savedMenuItem.getId()
        );

        return menuItemMapper.toResponse(
                savedMenuItem
        );
    }
}