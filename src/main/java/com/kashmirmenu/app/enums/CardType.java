package com.kashmirmenu.app.enums;

/** Card kind for CARD payments. Only the kind is stored, never card numbers or any card credentials. */
public enum CardType {

    CREDIT("Credit card"),
    DEBIT("Debit card"),
    OTHER("Other card");

    private final String label;

    CardType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
