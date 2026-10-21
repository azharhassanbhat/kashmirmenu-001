package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CategoryRepository
        extends JpaRepository<Category, Long> {

    boolean existsByRestaurantIdAndName(
            Long restaurantId,
            String name
    );

    Page<Category> findByRestaurantId(
            Long restaurantId,
            Pageable pageable
    );

    Optional<Category> findByIdAndRestaurantId(
            Long id,
            Long restaurantId
    );

    java.util.List<Category> findByRestaurantIdAndActiveTrueOrderByDisplayOrderAscNameAsc(
            Long restaurantId
    );

    /** For the menu import: a category the restaurant already has, whatever the capitalisation. */
    Optional<Category> findByRestaurantIdAndNameIgnoreCase(Long restaurantId, String name);

    long countByRestaurantIdAndActiveTrue(Long restaurantId);
}
