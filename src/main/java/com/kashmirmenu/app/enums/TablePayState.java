package com.kashmirmenu.app.enums;

/**
 * How far a bill (one order, one guest, or a whole table) has been paid. Worked out from amounts by
 * BillMath.payState, never stored.
 */
public enum TablePayState {
    /** Nothing billed: no orders, or every order was cancelled. */
    NO_ORDERS,
    /** Billed, and nothing has been received yet. A payment waiting for staff does not count as received. */
    UNPAID,
    /** Some money received and some still owed or waiting. */
    PARTIALLY_PAID,
    /** Everything billed has been received. */
    PAID
}
