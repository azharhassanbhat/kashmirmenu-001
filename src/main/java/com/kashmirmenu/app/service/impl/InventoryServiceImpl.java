package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.dto.request.CreateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateInventoryRequest;
import com.kashmirmenu.app.dto.request.UpdateStockRequest;
import com.kashmirmenu.app.dto.response.InventoryResponse;
import com.kashmirmenu.app.entity.Inventory;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.exception.DuplicateResourceException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.InventoryMapper;
import com.kashmirmenu.app.repository.InventoryRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final InventoryMapper inventoryMapper;
    private final CurrentUserService currentUserService;

    @Override
    public InventoryResponse createInventory(CreateInventoryRequest request) {

        // Taken from the caller, not from request.getRestaurantId(): the body used to decide which
        // restaurant the stock belonged to, so any authenticated user could write into another's.
        Restaurant restaurant = ownRestaurant();

        MenuItem menuItem = menuItemRepository.findById(request.getMenuItemId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Menu item not found with id: " + request.getMenuItemId()));

        if (!menuItem.getRestaurant().getId().equals(restaurant.getId())) {
            throw new ResourceNotFoundException(
                    "Menu item not found with id: " + request.getMenuItemId());
        }

        if (inventoryRepository.existsByMenuItem(menuItem)) {
            throw new DuplicateResourceException(
                    "Inventory already exists for menu item.");
        }

        Inventory inventory = inventoryMapper.toEntity(request);

        inventory.setRestaurant(restaurant);
        inventory.setMenuItem(menuItem);

// Explicitly set values from request
        inventory.setAvailableQuantity(request.getAvailableQuantity());
        inventory.setMinimumQuantity(request.getMinimumQuantity());

// Calculate inStock automatically
        inventory.setInStock(request.getAvailableQuantity() > 0);

        Inventory savedInventory = inventoryRepository.save(inventory);

        return inventoryMapper.toResponse(savedInventory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryResponse> getAllInventory() {

        // Was findAll(), which returned every restaurant's stock to any authenticated caller.
        return inventoryRepository.findByRestaurant(ownRestaurant())
                .stream()
                .map(inventoryMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponse getInventoryById(Long id) {

        Inventory inventory = ownInventory(id);

        return inventoryMapper.toResponse(inventory);
    }

    @Override
    public InventoryResponse updateInventory(
            Long id,
            UpdateInventoryRequest request) {

        Inventory inventory = ownInventory(id);

        inventoryMapper.updateEntity(request, inventory);

        Inventory updatedInventory = inventoryRepository.save(inventory);

        return inventoryMapper.toResponse(updatedInventory);
    }

    @Override
    public InventoryResponse updateStock(
            Long id,
            UpdateStockRequest request) {

        Inventory inventory = ownInventory(id);

        inventory.setAvailableQuantity(request.getAvailableQuantity());

        inventory.setInStock(request.getAvailableQuantity() > 0);

        Inventory updatedInventory = inventoryRepository.save(inventory);

        return inventoryMapper.toResponse(updatedInventory);
    }

    @Override
    public void deleteInventory(Long id) {

        Inventory inventory = ownInventory(id);

        inventoryRepository.delete(inventory);
    }

    private Restaurant ownRestaurant() {

        Restaurant restaurant = currentUserService.getCurrentRestaurant();

        if (restaurant == null) {
            throw new ResourceNotFoundException("This account is not attached to a restaurant.");
        }
        return restaurant;
    }

    /**
     * Loads a stock row and refuses it if it belongs to another restaurant. Answers exactly as a
     * missing row does, so ids cannot be probed across tenants.
     */
    private Inventory ownInventory(Long id) {

        Inventory inventory = inventoryRepository.findById(id)
                .filter(found -> found.getRestaurant().getId().equals(ownRestaurant().getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventory not found with id: " + id));

        return inventory;
    }
}
