package com.kashmirmenu.app.enums;

public enum RoleName {

    /**
     * Administers the whole platform. Deliberately separate from every restaurant role and
     * excluded from all restaurant-scoped authorization: a platform owner has no restaurant, so
     * those endpoints are not merely unnecessary but meaningless for this role.
     */
    PLATFORM_OWNER,

    /** Restaurant-scoped super user, despite the name. Not a platform role. */
    ADMIN,

    RESTAURANT_OWNER,

    MANAGER,

    CHEF,

    WAITER,

    CASHIER,

    CUSTOMER
}