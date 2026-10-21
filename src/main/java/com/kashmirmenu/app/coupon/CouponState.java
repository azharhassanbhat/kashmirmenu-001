package com.kashmirmenu.app.coupon;

/** Why a coupon can or cannot be used right now. One value per message a diner might see. */
public enum CouponState {

    VALID(null),
    NOT_FOUND("That code isn't valid for this restaurant."),
    INACTIVE("That coupon is no longer available."),
    NOT_STARTED("That coupon isn't active yet."),
    EXPIRED("That coupon has expired."),
    USED_UP("That coupon has been fully claimed."),
    BELOW_MINIMUM("Spend a little more to use this coupon."),
    BELOW_MINIMUM_ITEMS("Add another dish to use this coupon."),
    EMPTY_CART("Add something to the cart before applying a coupon.");

    private final String message;

    CouponState(String message) {
        this.message = message;
    }

    public boolean isValid() {
        return this == VALID;
    }

    public String message() {
        return message;
    }
}
