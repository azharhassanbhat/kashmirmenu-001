package com.kashmirmenu.app.security.authorization;

import com.kashmirmenu.app.entity.Restaurant;

public interface AuthorizationService {

    void verifyRestaurantOwnership(Restaurant restaurant);

}