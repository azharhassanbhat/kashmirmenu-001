package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.CategoryRequest;
import com.kashmirmenu.app.dto.response.CategoryResponse;
import com.kashmirmenu.app.entity.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public Category toEntity(CategoryRequest request) {

        if (request == null) {
            return null;
        }

        Category category = new Category();

        mapRequestToEntity(category, request);

        return category;
    }

    public CategoryResponse toResponse(Category category) {

        CategoryResponse.CategoryResponseBuilder builder =
                CategoryResponse.builder()
                        .id(category.getId())
                        .name(category.getName())
                        .description(category.getDescription())
                        .displayOrder(category.getDisplayOrder())
                        .active(category.getActive());

        if (category.getRestaurant() != null) {

            builder.restaurantId(category.getRestaurant().getId());

            builder.restaurantName(category.getRestaurant().getName());
        }

        return builder.build();
    }
    private void mapRequestToEntity(Category category,
                                    CategoryRequest request) {

        category.setName(request.getName());
        category.setDescription(request.getDescription());
        category.setDisplayOrder(request.getDisplayOrder());
        category.setActive(request.getActive());
    }

    public void updateEntity(Category category,
                             CategoryRequest request) {

        if (category == null || request == null) {
            return;
        }

        mapRequestToEntity(category, request);
    }
}