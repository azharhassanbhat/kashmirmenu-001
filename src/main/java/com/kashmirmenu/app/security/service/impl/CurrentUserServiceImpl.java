package com.kashmirmenu.app.security.service.impl;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.exception.AccessDeniedException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.UserRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentUserServiceImpl implements CurrentUserService {

    private final UserRepository userRepository;


    @Override
    public User getCurrentUser() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        String email = authentication.getName();

        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResourceNotFoundException("User not found"));

    }

    @Override
    public Restaurant getCurrentRestaurant() {
        return getCurrentUser().getRestaurant();
    }

    /**
     * The caller's restaurant id, for the restaurant-scoped APIs.
     *
     * <p>Throws rather than dereferencing null. Some accounts legitimately have no restaurant - a
     * platform owner, or a customer - and before this they produced a NullPointerException and a 500
     * from any restaurant endpoint, which read as a server fault rather than as the refusal it is.
     */
    @Override
    public Long getCurrentRestaurantId() {

        Restaurant restaurant = getCurrentRestaurant();

        if (restaurant == null) {
            throw new AccessDeniedException(
                    "This account is not attached to a restaurant.");
        }
        return restaurant.getId();
    }
}