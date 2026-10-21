package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.RestaurantBillingSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantBillingSettingRepository extends JpaRepository<RestaurantBillingSetting, Long> {

    Optional<RestaurantBillingSetting> findByRestaurantId(Long restaurantId);
}
