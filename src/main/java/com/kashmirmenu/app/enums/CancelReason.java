package com.kashmirmenu.app.enums;

/**
 * Why staff cancelled an order. A fixed list, so reasons can be counted and compared rather than
 * being free text. The label is what staff pick and what the guest is shown; the guest never sees
 * the free-text note, which may be internal.
 */
public enum CancelReason {
    OUT_OF_STOCK("A dish is out of stock"),
    KITCHEN_CANNOT_MAKE("The kitchen cannot make it right now"),
    GUEST_REQUEST("The guest asked to cancel"),
    DUPLICATE_ORDER("It was ordered twice"),
    ORDERED_BY_MISTAKE("It was ordered by mistake"),
    OTHER("Another reason");

    private final String label;

    CancelReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
