package com.kashmirmenu.app.service;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.CategoryRequest;
import com.kashmirmenu.app.dto.response.CategoryResponse;

public interface CategoryService {

    CategoryResponse createCategory(CategoryRequest request);

    PageResponse<CategoryResponse> getAllCategories(
            int page,
            int size,
            String sortBy,
            String direction);

    CategoryResponse getCategoryById(Long id);

    CategoryResponse updateCategory(Long id,
                                    CategoryRequest request);

    CategoryResponse activateCategory(Long id);

    CategoryResponse deactivateCategory(Long id);
}