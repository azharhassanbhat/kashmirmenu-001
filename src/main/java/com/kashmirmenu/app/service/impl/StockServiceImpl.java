package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.entity.Inventory;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderItem;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.repository.InventoryRepository;
import com.kashmirmenu.app.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the inventory counts in step with orders.
 *
 * <p>Stock was previously only ever edited by hand on the Inventory page: placing an order did not
 * touch it, so the numbers drifted away from reality as soon as the restaurant started trading.
 *
 * <p>Only dishes that have an inventory row are tracked. Everything else is treated as unlimited,
 * which is what a restaurant expects: you track the twelve portions of mutton you prepped, not salt.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class StockServiceImpl implements StockService {

    private final InventoryRepository inventoryRepository;

    @Override
    public void consumeForOrder(Order order) {

        // Locked in a deterministic order (by menu item id), not in line order. Two orders containing
        // the same two dishes in opposite line order would otherwise acquire the same two inventory
        // rows in opposite order and could deadlock. Order.items is a List with no @OrderBy, so its
        // iteration order is not guaranteed stable between transactions.
        for (OrderItem item : lockOrdered(order)) {

            Long menuItemId = item.getMenuItem().getId();
            Optional<Inventory> tracked = inventoryRepository.findByMenuItemIdForUpdate(menuItemId);

            if (tracked.isEmpty()) {
                continue;
            }

            Inventory inventory = tracked.get();
            int available = available(inventory);
            int wanted = item.getQuantity();

            if (available < wanted) {
                // Unchecked, so the transaction rolls back: no part of the order is taken from stock.
                throw new BadRequestException(shortfallMessage(item.getItemName(), available));
            }

            apply(inventory, available - wanted);

            // Recorded on the line so restoration can give back exactly this. Deriving it later from
            // whether the dish is tracked at that moment credited stock that was never taken, because
            // a dish can start being tracked between placement and cancellation.
            item.setStockConsumed(wanted);
        }
    }

    @Override
    public void restoreForOrder(Order order) {

        // Same deterministic order as consumeForOrder: a cancellation and a placement touching the
        // same dishes must not take them in opposite orders either.
        for (OrderItem item : lockOrdered(order)) {

            int consumed = item.getStockConsumed() == null ? 0 : item.getStockConsumed();

            // Nothing was taken, so nothing goes back: either the dish was untracked when the order
            // was placed, or the row predates migration V26. Returning item.getQuantity() here was
            // the bug - it credited stock for orders that never deducted any.
            if (consumed <= 0) {
                continue;
            }

            inventoryRepository.findByMenuItemIdForUpdate(item.getMenuItem().getId())
                    .ifPresent(inventory -> apply(inventory, available(inventory) + consumed));

            // Cleared so a repeated restoration cannot credit twice. The status guard in
            // updateOrderStatus already prevents that, but this makes the method safe on its own.
            item.setStockConsumed(0);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertAvailable(Long menuItemId, int quantity) {

        inventoryRepository.findByMenuItemId(menuItemId).ifPresent(inventory -> {
            int available = available(inventory);
            if (available < quantity) {
                throw new BadRequestException(
                        shortfallMessage(inventory.getMenuItem().getName(), available));
            }
        });
    }

    /**
     * The order's lines sorted by menu item id, which is the order inventory rows are locked in.
     *
     * <p>Any stable global ordering removes the deadlock; the id is the simplest one that every
     * transaction agrees on without extra state.
     */
    private static List<OrderItem> lockOrdered(Order order) {

        return order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getMenuItem().getId()))
                .toList();
    }

    private void apply(Inventory inventory, int remaining) {
        inventory.setAvailableQuantity(remaining);
        // The flag follows the count, so the Inventory page and any stock filter stay consistent
        // with it instead of having to be corrected by hand.
        inventory.setInStock(remaining > 0);
        inventoryRepository.save(inventory);
    }

    private static int available(Inventory inventory) {
        return inventory.getAvailableQuantity() == null ? 0 : inventory.getAvailableQuantity();
    }

    private static String shortfallMessage(String dish, int available) {
        return available <= 0
                ? dish + " has just sold out."
                : "Only " + available + " left of " + dish + ".";
    }
}
