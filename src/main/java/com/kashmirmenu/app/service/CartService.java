package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.AddCartItemRequest;
import com.kashmirmenu.app.dto.request.CreateCartRequest;
import com.kashmirmenu.app.dto.request.UpdateCartItemRequest;
import com.kashmirmenu.app.dto.response.CartResponse;
import com.kashmirmenu.app.entity.DinerSession;

public interface CartService {

    CartResponse getOrCreateCart(CreateCartRequest request);

    /**
     * The cart for a guest, or - when {@code diner} is null - the table's staff cart. Every method
     * below follows the same rule: a guest only ever reaches their own cart.
     */
    CartResponse getCart(String tableQrIdentifier, DinerSession diner);

    CartResponse addItem(AddCartItemRequest request, DinerSession diner);

    CartResponse updateItem(
            Long cartItemId,
            UpdateCartItemRequest request,
            DinerSession diner);

    void removeItem(
            Long cartItemId,
            String tableQrIdentifier,
            DinerSession diner);

    void clearCart(String tableQrIdentifier, DinerSession diner);

    /** Holds a coupon against the cart. Throws BadRequestException if it cannot be used. */
    CartResponse applyCoupon(String tableQrIdentifier, String couponCode, DinerSession diner);

    CartResponse removeCoupon(String tableQrIdentifier, DinerSession diner);
}