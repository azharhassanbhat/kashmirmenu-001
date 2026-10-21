package com.kashmirmenu.app.enums;

/**
 * What a guest can ask for at the table without placing a food order.
 *
 * <p>{@link #label()} is the wording staff see and the guest gets back; the client may translate
 * or restyle it, but the stored value is always the enum name.
 */
public enum ServiceRequestType {
    CALL_WAITER("Call waiter"),
    DRINKING_WATER("Drinking water"),
    REQUEST_BILL("Bring the bill"),
    CUTLERY("Cutlery"),
    NAPKINS("Tissues / napkins"),
    EXTRA_PLATES("Extra plates"),
    CLEANING("Clean the table"),
    ASSISTANCE("Assistance"),
    OTHER("Other request");

    private final String label;

    ServiceRequestType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** OTHER is free text and may legitimately be raised more than once; every other type is one-at-a-time. */
    public boolean allowsSeveralOpen() {
        return this == OTHER;
    }

    /** The bill request is the one staff should never miss. */
    public boolean urgent() {
        return this == CALL_WAITER || this == REQUEST_BILL || this == ASSISTANCE;
    }
}
