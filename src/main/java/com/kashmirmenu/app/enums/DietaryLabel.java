package com.kashmirmenu.app.enums;

/** Dietary labels a restaurant may attach to a dish. Declared by staff, never inferred. */
public enum DietaryLabel {
    VEGAN("Vegan"),
    JAIN("Jain"),
    GLUTEN_FREE("Gluten free"),
    DAIRY_FREE("Dairy free"),
    NUT_FREE("Nut free"),
    HALAL("Halal");

    private final String label;

    DietaryLabel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
