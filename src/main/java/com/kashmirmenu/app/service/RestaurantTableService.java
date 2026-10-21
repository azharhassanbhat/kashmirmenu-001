package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.CreateRestaurantTableRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantTableRequest;
import com.kashmirmenu.app.dto.response.RestaurantTableResponse;

import java.util.List;

public interface RestaurantTableService {

    RestaurantTableResponse createTable(CreateRestaurantTableRequest request);

    RestaurantTableResponse updateTable(Long tableId, UpdateRestaurantTableRequest request);

    RestaurantTableResponse getTable(Long tableId);

    List<RestaurantTableResponse> getAllTables();

    void deleteTable(Long tableId);

    void activateTable(Long tableId);

    void deactivateTable(Long tableId);

    byte[] getTableQrCode(Long tableId);

    /** As above at a chosen size in pixels (larger prints sharper); the size is held to sensible limits. */
    byte[] getTableQrCode(Long tableId, int sizePixels);

    /** The restaurant's name and active tables, in natural order, for printing QR cards. */
    com.kashmirmenu.app.dto.response.QrCardsResponse getQrCards();
}