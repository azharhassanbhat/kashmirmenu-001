package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.Restaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long>,
        JpaSpecificationExecutor<MenuItem> {

    boolean existsByRestaurantIdAndCategoryIdAndName(
            Long restaurantId,
            Long categoryId,
            String name);
    List<MenuItem>
    findByRestaurantIdAndActiveTrueAndAvailableTrue(
            Long restaurantId);

    Page<MenuItem> findByRestaurantId(
            Long restaurantId,
            Pageable pageable);

    Page<MenuItem> findByCategoryId(
            Long categoryId,
            Pageable pageable);

    List<MenuItem> findByCategoryIdAndActiveTrueAndAvailableTrue(
            Long categoryId);

    List<MenuItem> findByCategoryIdAndActiveTrue(Long categoryId);

    Optional<MenuItem> findByIdAndActiveTrue(Long id);

    Optional<MenuItem> findByIdAndRestaurantId(
            Long menuItemId,
            Long restaurantId);

    Optional<MenuItem> findByIdAndCategoryId(
            Long menuItemId,
            Long categoryId);

    Optional<MenuItem> findByIdAndRestaurant(
            Long menuItemId,
            Restaurant restaurant);

    List<MenuItem> findByRestaurantIdAndActiveTrue(Long restaurantId);

    /** Dishes a guest can actually order right now. */
    long countByRestaurantIdAndActiveTrueAndAvailableTrue(Long restaurantId);

    long countByRestaurantId(Long restaurantId);
}
