package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.RestaurantLandingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantLandingSettingsRepository extends JpaRepository<RestaurantLandingSettings, Long> {

    Optional<RestaurantLandingSettings> findByRestaurantId(Long restaurantId);
}
