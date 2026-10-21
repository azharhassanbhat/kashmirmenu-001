package com.kashmirmenu.app.enums;

public enum NotificationType {

    ORDER_PLACED,

    ORDER_CONFIRMED,

    ORDER_PREPARING,

    ORDER_READY,

    ORDER_SERVED,

    PAYMENT_SUCCESS,

    PAYMENT_FAILED,

    COUPON_APPLIED,
    SERVICE_REQUEST_CREATED,
    SERVICE_REQUEST_UPDATED,

    /** A staff cancellation of an order. */
    ORDER_CANCELLED,

    /** A refund recorded against a payment. */
    PAYMENT_REFUNDED,

    /** A guest's rating of an order, or anonymous feedback from the landing page. */
    FEEDBACK_RECEIVED,

    /** Platform owner decisions about the restaurant itself. */
    RESTAURANT_APPROVED,
    RESTAURANT_REJECTED,
    RESTAURANT_SUSPENDED,
    RESTAURANT_REACTIVATED
}