package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantPaymentSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantPaymentSettingRepository
        extends JpaRepository<RestaurantPaymentSetting, Long> {

    Optional<RestaurantPaymentSetting> findByRestaurant(Restaurant restaurant);

    Optional<RestaurantPaymentSetting> findByRestaurantId(Long restaurantId);

    boolean existsByRestaurant(Restaurant restaurant);
}