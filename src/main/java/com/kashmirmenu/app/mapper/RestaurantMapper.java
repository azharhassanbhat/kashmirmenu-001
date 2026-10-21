package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.RestaurantRequest;
import com.kashmirmenu.app.dto.response.RestaurantResponse;
import com.kashmirmenu.app.entity.Restaurant;
import org.springframework.stereotype.Component;

@Component
public class RestaurantMapper {

    public Restaurant toEntity(RestaurantRequest request) {

        if (request == null) {
            return null;
        }

        Restaurant restaurant = new Restaurant();

        mapRequestToEntity(restaurant, request);

        return restaurant;
    }

    public RestaurantResponse toResponse(Restaurant restaurant) {

        if (restaurant == null) {
            return null;
        }

        return RestaurantResponse.builder()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .slug(restaurant.getSlug())
                .email(restaurant.getEmail())
                .phone(restaurant.getPhone())
                .address(restaurant.getAddress())
                .city(restaurant.getCity())
                .state(restaurant.getState())
                .country(restaurant.getCountry())
                .logo(restaurant.getLogo())
                .primaryColor(restaurant.getPrimaryColor())
                .secondaryColor(restaurant.getSecondaryColor())
                .googlePlaceId(restaurant.getGooglePlaceId())
                .active(restaurant.getActive())
                .createdAt(restaurant.getCreatedAt())
                .updatedAt(restaurant.getUpdatedAt())
                .build();
    }

    private void mapRequestToEntity(Restaurant restaurant,
                                    RestaurantRequest request) {

        restaurant.setName(request.getName());
        restaurant.setEmail(request.getEmail());
        restaurant.setPhone(request.getPhone());
        restaurant.setAddress(request.getAddress());
        restaurant.setCity(request.getCity());
        restaurant.setState(request.getState());
        restaurant.setCountry(request.getCountry());
        restaurant.setLogo(request.getLogo());
        restaurant.setPrimaryColor(request.getPrimaryColor());
        restaurant.setSecondaryColor(request.getSecondaryColor());
    }
    public void updateEntity(Restaurant restaurant,
                             RestaurantRequest request) {

        if (restaurant == null || request == null) {
            return;
        }

        mapRequestToEntity(restaurant, request);
    }
}