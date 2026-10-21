package com.kashmirmenu.app.enums;

/**
 * The structured meaning of a notification, so clients choose a sound (and decide who should hear it) from
 * a stable code instead of reading the display text.
 *
 * <p>{@link NotificationType} says what table the event came from; this says what it is. They differ where one
 * type covers several events: a {@code SERVICE_REQUEST_CREATED} is a {@link #WAITER_CALL}, a
 * {@link #BILL_REQUEST} or an ordinary {@link #TABLE_REQUEST}. Rows saved before this existed have no stored
 * kind; {@link #defaultFor} gives them the one their type implies.
 */
public enum NotificationEventKind {
    NEW_ORDER,
    ORDER_CONFIRMED,
    ORDER_PREPARING,
    ORDER_READY,
    ORDER_SERVED,
    ORDER_CANCELLED,
    PAYMENT_SUCCESS,
    PAYMENT_FAILED,
    PAYMENT_REFUNDED,
    COUPON_APPLIED,
    WAITER_CALL,
    BILL_REQUEST,
    TABLE_REQUEST,
    TABLE_REQUEST_UPDATE,
    FEEDBACK,
    RESTAURANT_APPROVED,
    RESTAURANT_REJECTED,
    RESTAURANT_SUSPENDED,
    RESTAURANT_REACTIVATED;

    /** The kind a notification of this type has when nothing more specific is known. */
    public static NotificationEventKind defaultFor(NotificationType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case ORDER_PLACED -> NEW_ORDER;
            case ORDER_CONFIRMED -> ORDER_CONFIRMED;
            case ORDER_PREPARING -> ORDER_PREPARING;
            case ORDER_READY -> ORDER_READY;
            case ORDER_SERVED -> ORDER_SERVED;
            case ORDER_CANCELLED -> ORDER_CANCELLED;
            case PAYMENT_SUCCESS -> PAYMENT_SUCCESS;
            case PAYMENT_FAILED -> PAYMENT_FAILED;
            case PAYMENT_REFUNDED -> PAYMENT_REFUNDED;
            case COUPON_APPLIED -> COUPON_APPLIED;
            case SERVICE_REQUEST_CREATED -> TABLE_REQUEST;
            case SERVICE_REQUEST_UPDATED -> TABLE_REQUEST_UPDATE;
            case FEEDBACK_RECEIVED -> FEEDBACK;
            case RESTAURANT_APPROVED -> RESTAURANT_APPROVED;
            case RESTAURANT_REJECTED -> RESTAURANT_REJECTED;
            case RESTAURANT_SUSPENDED -> RESTAURANT_SUSPENDED;
            case RESTAURANT_REACTIVATED -> RESTAURANT_REACTIVATED;
        };
    }

    /** The kind of a newly raised table request. */
    public static NotificationEventKind forNewRequest(ServiceRequestType type) {
        if (type == null) {
            return TABLE_REQUEST;
        }
        return switch (type) {
            case CALL_WAITER, ASSISTANCE -> WAITER_CALL;
            case REQUEST_BILL -> BILL_REQUEST;
            default -> TABLE_REQUEST;
        };
    }
}
