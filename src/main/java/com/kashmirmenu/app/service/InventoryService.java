package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.CreateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateStockRequest;
import com.kashmirmenu.app.dto.response.InventoryResponse;

import java.util.List;

public interface InventoryService {

    InventoryResponse createInventory(CreateInventoryRequest request);

    List<InventoryResponse> getAllInventory();

    InventoryResponse getInventoryById(Long id);

    InventoryResponse updateInventory(
            Long id,
            UpdateInventoryRequest request);

    InventoryResponse updateStock(
            Long id,
            UpdateStockRequest request);

    void deleteInventory(Long id);
}