package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.service.qr.QrRules;
import com.kashmirmenu.app.dto.request.CreateRestaurantTableRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantTableRequest;
import com.kashmirmenu.app.dto.response.RestaurantTableResponse;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.RestaurantTableMapper;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.RestaurantTableService;
import com.kashmirmenu.app.service.qr.QrCodeService;
import com.kashmirmenu.app.exception.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class RestaurantTableServiceImpl implements RestaurantTableService {

    private final RestaurantTableRepository restaurantTableRepository;
    private final RestaurantTableMapper restaurantTableMapper;
    private final CurrentUserService currentUserService;
    private final QrCodeService qrCodeService;

    @org.springframework.beans.factory.annotation.Value("${app.public-base-url}")
    private String publicBaseUrl;

    @Override
    public RestaurantTableResponse createTable(
            CreateRestaurantTableRequest request) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        log.info(
                "Creating restaurant table with code={} for restaurant={}",
                request.getTableCode(),
                restaurant.getId()
        );

        if (restaurantTableRepository
                .existsByRestaurantAndTableCode(
                        restaurant,
                        request.getTableCode())) {

            throw new IllegalArgumentException(
                    "Table code already exists for this restaurant."
            );
        }

        RestaurantTable restaurantTable =
                restaurantTableMapper.toEntity(request);

        restaurantTable.setRestaurant(restaurant);

        RestaurantTable savedTable =
                restaurantTableRepository.save(restaurantTable);

        log.info(
                "Restaurant table created successfully. id={}",
                savedTable.getId()
        );

        return restaurantTableMapper.toResponse(savedTable);
    }

    @Override
    public RestaurantTableResponse updateTable(
            Long tableId,
            UpdateRestaurantTableRequest request) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        log.info(
                "Updating restaurant table. id={}, restaurant={}",
                tableId,
                restaurant.getId()
        );

        RestaurantTable restaurantTable =
                getRestaurantTable(tableId, restaurant);

        if (!restaurantTable.getTableCode()
                .equals(request.getTableCode())
                && restaurantTableRepository
                .existsByRestaurantAndTableCode(
                        restaurant,
                        request.getTableCode())) {

            throw new IllegalArgumentException(
                    "Table code already exists for this restaurant."
            );
        }

        restaurantTableMapper.updateEntity(
                request,
                restaurantTable
        );

        RestaurantTable savedTable =
                restaurantTableRepository.save(
                        restaurantTable
                );

        return restaurantTableMapper.toResponse(savedTable);
    }

    @Override
    @Transactional(readOnly = true)
    public RestaurantTableResponse getTable(Long tableId) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        log.info(
                "Fetching restaurant table. id={}, restaurant={}",
                tableId,
                restaurant.getId()
        );

        RestaurantTable restaurantTable =
                getRestaurantTable(tableId, restaurant);

        return restaurantTableMapper.toResponse(
                restaurantTable
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<RestaurantTableResponse> getAllTables() {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        log.info(
                "Fetching all tables for restaurant={}",
                restaurant.getId()
        );

        return restaurantTableRepository
                .findAllByRestaurant(restaurant)
                .stream()
                .map(restaurantTableMapper::toResponse)
                .toList();
    }

    @Override
    public void deleteTable(Long tableId) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        RestaurantTable restaurantTable =
                getRestaurantTable(tableId, restaurant);

        // A table that has ever been scanned has visits, carts and orders pointing at it, and the database
        // refuses to delete it. Flushed here so that refusal happens inside this try, where it can be
        // explained, instead of at commit, where it surfaces as a generic server error.
        try {
            restaurantTableRepository.delete(
                    restaurantTable
            );
            restaurantTableRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "This table has orders or sessions on record, so it cannot be deleted. "
                            + "Deactivate it instead: its QR code stops working and its history is kept.");
        }

        log.info(
                "Restaurant table deleted. id={}, restaurant={}",
                tableId,
                restaurant.getId()
        );
    }

    @Override
    public void activateTable(Long tableId) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        RestaurantTable restaurantTable =
                getRestaurantTable(tableId, restaurant);

        restaurantTable.setActive(true);

        restaurantTableRepository.save(
                restaurantTable
        );
    }

    @Override
    public void deactivateTable(Long tableId) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        RestaurantTable restaurantTable =
                getRestaurantTable(tableId, restaurant);

        restaurantTable.setActive(false);

        restaurantTableRepository.save(
                restaurantTable
        );
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getTableQrCode(Long tableId) {
        return getTableQrCode(tableId, QrRules.DEFAULT_SIZE);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getTableQrCode(Long tableId, int sizePixels) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        RestaurantTable restaurantTable =
                getRestaurantTable(tableId, restaurant);

        // The base address is tidied (no doubled slash) because a printed code can never be recalled.
        String url = QrRules.tableUrl(publicBaseUrl, restaurantTable.getQrIdentifier());

        return qrCodeService.generateQrCode(url, sizePixels, sizePixels);
    }

    @Override
    @Transactional(readOnly = true)
    public com.kashmirmenu.app.dto.response.QrCardsResponse getQrCards() {

        Restaurant restaurant = currentUserService.getCurrentRestaurant();

        var cards = restaurantTableRepository
                .findAllByRestaurant(restaurant)
                .stream()
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .sorted(java.util.Comparator.comparing(RestaurantTable::getTableName, QrRules.NATURAL_ORDER))
                .map(t -> new com.kashmirmenu.app.dto.response.QrCardsResponse.Card(
                        t.getId(), t.getTableName(), t.getTableCode()))
                .toList();

        return new com.kashmirmenu.app.dto.response.QrCardsResponse(restaurant.getName(), cards);
    }

    private RestaurantTable getRestaurantTable(
            Long tableId,
            Restaurant restaurant) {

        return restaurantTableRepository
                .findByIdAndRestaurant(
                        tableId,
                        restaurant
                )
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Restaurant table not found with id: "
                                        + tableId
                        ));
    }
}