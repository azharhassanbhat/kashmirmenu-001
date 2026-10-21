package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Notification;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Restaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Paged: notifications accumulate for the life of the restaurant (one per order placed, per
     * payment, per status change), and this read covered the whole history.
     */
    Page<Notification> findByRestaurantOrderByCreatedAtDesc(
            Restaurant restaurant,
            org.springframework.data.domain.Pageable pageable);

    List<Notification> findByRestaurantAndReadFalseOrderByCreatedAtDesc(
            Restaurant restaurant);

    List<Notification> findByOrderOrderByCreatedAtAsc(
            Order order);

    long countByRestaurantAndReadFalse(
            Restaurant restaurant);

    long countByRestaurantIdAndReadFalse(Long restaurantId);
}