package com.kashmirmenu.app.enums;

/**
 * A settlement's state, worked out from the payments it is made of (see SettlementRules.statusOf).
 * It is never stored, so it cannot drift from the payments.
 */
public enum SettlementStatus {
    /** At least one payment is still waiting for staff to confirm it. */
    PENDING,
    /** Every payment was received. */
    SUCCESS,
    /** Nothing was received: every payment failed. */
    FAILED,
    /** Nothing was received: every payment was cancelled. */
    CANCELLED,
    /** Some payments were received and some were not (an order cancelled mid-way, say). */
    PARTIAL
}
