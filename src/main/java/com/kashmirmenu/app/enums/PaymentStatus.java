package com.kashmirmenu.app.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentStatus {

    PENDING("Pending"),
    SUCCESS("Successful"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    PARTIALLY_REFUNDED("Partially refunded"),
    REFUNDED("Refunded");

    /** Statuses in which the money was actually received. Only these count as collected revenue. */
    public static final Set<PaymentStatus> CAPTURED =
            EnumSet.of(SUCCESS, PARTIALLY_REFUNDED, REFUNDED);

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True once the payment was received (even if it has since been refunded in whole or part). */
    public boolean isCaptured() {
        return CAPTURED.contains(this);
    }
}
