package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.dto.request.RestaurantRequest;
import com.kashmirmenu.app.dto.request.RestaurantSearchRequest;
import com.kashmirmenu.app.dto.response.RestaurantResponse;
import com.kashmirmenu.app.config.RestaurantApprovalProperties;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.RestaurantMapper;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.RestaurantService;
import com.kashmirmenu.app.specification.RestaurantSpecification;
import com.kashmirmenu.app.util.PageResponseUtils;
import com.kashmirmenu.app.util.PageableUtils;
import com.kashmirmenu.app.util.SlugUtil;
import com.kashmirmenu.app.util.SpecificationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import com.kashmirmenu.app.security.authorization.AuthorizationService;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantServiceImpl implements RestaurantService {

    private final AuthorizationService authorizationService;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantMapper restaurantMapper;
    private final SlugUtil slugUtil;
    private final CurrentUserService currentUserService;
    private final RestaurantApprovalProperties approvalProperties;
    private final com.kashmirmenu.app.platform.PlatformEventPublisher platformEvents;

    @Override
    public RestaurantResponse createRestaurant(RestaurantRequest request) {

        log.info("Creating restaurant with name: {}", request.getName());

        Restaurant restaurant = restaurantMapper.toEntity(request);

        restaurant.setSlug(slugUtil.generateUniqueSlug(request.getName()));

        restaurant.setOwner(currentUserService.getCurrentUser());

        // An additional restaurant needs the platform owner's approval like any other.
        restaurant.setApprovalStatus(approvalProperties.initialStatus());

        RestaurantResponse response = saveRestaurant(restaurant);

        if (restaurant.getApprovalStatus() == RestaurantStatus.PENDING) {
            platformEvents.restaurantRegistered(restaurant);
        }

        log.info("Restaurant created successfully with id={}",
                response.getId());

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RestaurantResponse> getAllRestaurants(
            int page,
            int size,
            String sortBy,
            String direction) {
        log.info("Fetching restaurants. page={}, size={}, sortBy={}, direction={}",
                page, size, sortBy, direction);
        Pageable pageable = PageableUtils.createPageable(
                page,
                size,
                sortBy,
                direction,
                PageableUtils.RESTAURANT_SORT
        );

        // Listings are for diners and owners browsing: only restaurants cleared to operate appear.
        Page<Restaurant> restaurantPage =
                restaurantRepository.findAll(RestaurantSpecification.isApproved(), pageable);

        return PageResponseUtils.fromPage(
                restaurantPage,
                restaurantMapper::toResponse
        );
    }

    /**
     * Generates a unique slug.
     * Example:
     * kashmir-cafe
     * kashmir-cafe-2
     * kashmir-cafe-3
     */


    @Override
    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantById(Long id) {
        log.info("Fetching restaurant with id={}", id);
        Restaurant restaurant = getRestaurant(id);

        // A restaurant that is not approved is visible only to its own staff: anyone else gets the
        // same answer as for an id that does not exist.
        if (restaurant.getApprovalStatus() != RestaurantStatus.APPROVED) {
            var current = currentUserService.getCurrentRestaurant();
            if (current == null || !current.getId().equals(restaurant.getId())) {
                throw new ResourceNotFoundException("Restaurant not found with id: " + id);
            }
        }
        log.debug("Restaurant fetched successfully. id={}", id);

        return restaurantMapper.toResponse(restaurant);
    }
    @Override
    public RestaurantResponse updateRestaurant(Long id, RestaurantRequest request) {

        log.info("Updating restaurant with id={}", id);

        Restaurant restaurant = getRestaurant(id);

        // Verify that the logged-in user owns this restaurant
        authorizationService.verifyRestaurantOwnership(restaurant);

        restaurantMapper.updateEntity(restaurant, request);

        RestaurantResponse response = saveRestaurant(restaurant);

        log.info("Restaurant updated successfully with id={}",
                response.getId());

        return response;
    }
    @Override
    public RestaurantResponse updateGooglePlaceId(Long id, String googlePlaceId) {

        Restaurant restaurant = getRestaurant(id);

        authorizationService.verifyRestaurantOwnership(restaurant);

        restaurant.setGooglePlaceId(
                googlePlaceId == null || googlePlaceId.isBlank() ? null : googlePlaceId.trim());

        log.info("Google Place ID {} for restaurant id={}",
                restaurant.getGooglePlaceId() == null ? "cleared" : "set", id);

        return saveRestaurant(restaurant);
    }

    @Override
    public RestaurantResponse deactivateRestaurant(Long id) {

        log.info("Deactivating restaurant with id={}", id);

        Restaurant restaurant = getRestaurant(id);

        authorizationService.verifyRestaurantOwnership(restaurant);

        restaurant.setActive(false);

        RestaurantResponse response = saveRestaurant(restaurant);

        log.info("Restaurant deactivated successfully with id={}", id);

        return response;
    }

    @Override
    public RestaurantResponse activateRestaurant(Long id) {

        log.info("Activating restaurant with id={}", id);

        Restaurant restaurant = getRestaurant(id);

        authorizationService.verifyRestaurantOwnership(restaurant);

        restaurant.setActive(true);

        RestaurantResponse response = saveRestaurant(restaurant);

        log.info("Restaurant activated successfully with id={}", id);

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RestaurantResponse> searchRestaurants(RestaurantSearchRequest request) {
        log.info("Searching restaurants with filters - name={}, city={}, active={}",
                request.getName(),
                request.getCity(),
                request.getActive());
        Pageable pageable = PageableUtils.createPageable(
                request.getPage(),
                request.getSize(),
                request.getSortBy(),
                request.getDirection(),
                PageableUtils.RESTAURANT_SORT
        );

        List<Specification<Restaurant>> specifications = new ArrayList<>();

        specifications.add(RestaurantSpecification.isApproved());

        if (request.getName() != null && !request.getName().isBlank()) {
            specifications.add(RestaurantSpecification.hasName(request.getName()));
        }

        if (request.getCity() != null && !request.getCity().isBlank()) {
            specifications.add(RestaurantSpecification.hasCity(request.getCity()));
        }

        if (request.getActive() != null) {
            specifications.add(RestaurantSpecification.isActive(request.getActive()));
        }

        Specification<Restaurant> specification =
                SpecificationUtils.allOf(specifications);

        Page<Restaurant> restaurantPage = restaurantRepository.findAll(specification, pageable);

        List<RestaurantResponse> restaurants = restaurantPage.getContent()
                .stream()
                .map(restaurantMapper::toResponse)
                .toList();
        log.info("Search completed. {} restaurants found.",
                restaurantPage.getTotalElements());
        return PageResponse.<RestaurantResponse>builder()
                .items(restaurants)
                .page(restaurantPage.getNumber())
                .size(restaurantPage.getSize())
                .totalItems(restaurantPage.getTotalElements())
                .totalPages(restaurantPage.getTotalPages())
                .hasNext(restaurantPage.hasNext())
                .hasPrevious(restaurantPage.hasPrevious())
                .build();
    }

    private String generateUniqueSlug(String slug) {

        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Restaurant slug cannot be empty.");
        }

        String uniqueSlug = slug;
        int counter = 2;

        while (restaurantRepository.existsBySlug(uniqueSlug)) {
            log.warn("Slug '{}' already exists. Trying another slug.", uniqueSlug);
            uniqueSlug = slug + "-" + counter;
            counter++;
        }
        log.debug("Final unique slug: {}", uniqueSlug);
        return uniqueSlug;
    }

    private Restaurant getRestaurant(Long id) {

        log.debug("Fetching restaurant {}", id);

        return restaurantRepository.findById(id)
                .orElseThrow(() -> {

                    log.warn("Restaurant not found. id={}", id);

                    return new ResourceNotFoundException(
                            "Restaurant not found with id: " + id);
                });
    }

    private RestaurantResponse saveRestaurant(Restaurant restaurant) {

        Restaurant savedRestaurant = restaurantRepository.save(restaurant);

        log.debug("Restaurant saved. id={}", savedRestaurant.getId());

        return restaurantMapper.toResponse(savedRestaurant);
    }
}