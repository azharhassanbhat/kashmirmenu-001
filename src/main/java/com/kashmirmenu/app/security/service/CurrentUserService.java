package com.kashmirmenu.app.security.service;

import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.entity.Restaurant;

public interface CurrentUserService {

    User getCurrentUser();

    Restaurant getCurrentRestaurant();

    Long getCurrentRestaurantId();
}