package com.kashmirmenu.app.service;

import com.kashmirmenu.app.entity.Order;

public interface StockService {

    /**
     * Takes the order's items out of stock. Dishes with no inventory row are untracked and always
     * available; a tracked dish without enough left throws BadRequestException naming it, which rolls
     * the whole order back rather than overselling part of it.
     */
    void consumeForOrder(Order order);

    /** Puts the order's items back, for a cancellation. */
    void restoreForOrder(Order order);

    /**
     * Checks a prospective basket without changing anything, for the friendlier error the diner gets
     * while adding to the cart rather than at checkout.
     *
     * @param menuItemId the dish being added or changed
     * @param quantity   the total quantity the cart would hold
     */
    void assertAvailable(Long menuItemId, int quantity);
}
