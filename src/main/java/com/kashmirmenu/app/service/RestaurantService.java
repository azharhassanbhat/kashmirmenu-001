package com.kashmirmenu.app.service;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.RestaurantRequest;
import com.kashmirmenu.app.dto.request.RestaurantSearchRequest;
import com.kashmirmenu.app.dto.response.RestaurantResponse;


public interface RestaurantService {

    RestaurantResponse createRestaurant(RestaurantRequest request);
    PageResponse<RestaurantResponse> getAllRestaurants(
            int page,
            int size,
            String sortBy,
            String direction);
    RestaurantResponse getRestaurantById(Long id);
    RestaurantResponse updateRestaurant(Long id, RestaurantRequest request);

    /** Sets (or clears, when blank) the Google Place ID. Owner of the restaurant or ADMIN only. */
    RestaurantResponse updateGooglePlaceId(Long id, String googlePlaceId);
    RestaurantResponse deactivateRestaurant(Long id);
    RestaurantResponse activateRestaurant(Long id);
    PageResponse<RestaurantResponse> searchRestaurants(RestaurantSearchRequest request);
}