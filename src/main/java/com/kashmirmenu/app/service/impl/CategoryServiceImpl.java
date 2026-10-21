package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.CategoryRequest;
import com.kashmirmenu.app.dto.response.CategoryResponse;
import com.kashmirmenu.app.entity.Category;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.exception.DuplicateResourceException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.CategoryMapper;
import com.kashmirmenu.app.repository.CategoryRepository;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.CategoryService;
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
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final CurrentUserService currentUserService;

    @Override
    public CategoryResponse createCategory(CategoryRequest request) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Creating category '{}' for restaurant {}",
                request.getName(),
                restaurantId
        );

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        if (categoryRepository.existsByRestaurantIdAndName(
                restaurantId,
                request.getName())) {

            log.warn(
                    "Category '{}' already exists for restaurant {}",
                    request.getName(),
                    restaurantId
            );

            throw new DuplicateResourceException(
                    "Category already exists for this restaurant."
            );
        }

        Category category =
                categoryMapper.toEntity(request);

        category.setRestaurant(restaurant);

        CategoryResponse response =
                saveCategory(category);

        log.info(
                "Category '{}' created successfully with id={}",
                response.getName(),
                response.getId()
        );

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> getAllCategories(
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
                        PageableUtils.CATEGORY_SORT
                );

        Page<Category> categoryPage =
                categoryRepository.findByRestaurantId(
                        restaurantId,
                        pageable
                );

        return PageResponseUtils.fromPage(
                categoryPage,
                categoryMapper::toResponse
        );
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse getCategoryById(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        Category category =
                getCategory(id, restaurantId);

        return categoryMapper.toResponse(category);
    }

    @Override
    public CategoryResponse updateCategory(
            Long id,
            CategoryRequest request) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Updating category {} for restaurant {}",
                id,
                restaurantId
        );

        Category category =
                getCategory(id, restaurantId);

        if (!category.getName().equalsIgnoreCase(request.getName())
                && categoryRepository.existsByRestaurantIdAndName(
                restaurantId,
                request.getName())) {

            log.warn(
                    "Category '{}' already exists for restaurant {}",
                    request.getName(),
                    restaurantId
            );

            throw new DuplicateResourceException(
                    "Category already exists for this restaurant."
            );
        }

        categoryMapper.updateEntity(
                category,
                request
        );

        CategoryResponse response =
                saveCategory(category);

        log.info(
                "Category {} updated successfully.",
                id
        );

        return response;
    }

    @Override
    public CategoryResponse activateCategory(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Activating category {} for restaurant {}",
                id,
                restaurantId
        );

        Category category =
                getCategory(id, restaurantId);

        category.setActive(true);

        CategoryResponse response =
                saveCategory(category);

        log.info(
                "Category {} activated successfully.",
                id
        );

        return response;
    }

    @Override
    public CategoryResponse deactivateCategory(Long id) {

        Long restaurantId =
                currentUserService.getCurrentRestaurantId();

        log.info(
                "Deactivating category {} for restaurant {}",
                id,
                restaurantId
        );

        Category category =
                getCategory(id, restaurantId);

        category.setActive(false);

        CategoryResponse response =
                saveCategory(category);

        log.info(
                "Category {} deactivated successfully.",
                id
        );

        return response;
    }

    private Category getCategory(
            Long id,
            Long restaurantId) {

        log.debug(
                "Fetching category {} for restaurant {}",
                id,
                restaurantId
        );

        return categoryRepository
                .findByIdAndRestaurantId(id, restaurantId)
                .orElseThrow(() -> {

                    log.warn(
                            "Category {} not found for restaurant {}",
                            id,
                            restaurantId
                    );

                    return new ResourceNotFoundException(
                            "Category not found."
                    );
                });
    }

    private CategoryResponse saveCategory(
            Category category) {

        Category savedCategory =
                categoryRepository.save(category);

        log.debug(
                "Category saved. id={}",
                savedCategory.getId()
        );

        return categoryMapper.toResponse(savedCategory);
    }
}