package com.kashmirmenu.app.enums;

/**
 * Where a restaurant stands with the platform. Only APPROVED restaurants may operate: staff
 * endpoints and the public QR flow refuse everything else.
 */
public enum RestaurantStatus {

    /** Registered, awaiting the platform owner's decision. */
    PENDING,

    /** May operate. */
    APPROVED,

    /** Declined at registration. The owner can see the reason. */
    REJECTED,

    /** Was approved, then switched off by the platform owner. Reversible. */
    SUSPENDED
}
