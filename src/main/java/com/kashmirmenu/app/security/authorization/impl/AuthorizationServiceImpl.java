package com.kashmirmenu.app.security.authorization.impl;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.exception.AccessDeniedException;
import com.kashmirmenu.app.security.authorization.AuthorizationService;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private final CurrentUserService currentUserService;

    @Override
    public void verifyRestaurantOwnership(Restaurant restaurant) {

        User currentUser = currentUserService.getCurrentUser();

        if (!restaurant.getOwner().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException(
                    "You are not allowed to modify this restaurant."
            );
        }
    }
}