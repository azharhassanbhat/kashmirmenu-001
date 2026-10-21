package com.kashmirmenu.app.enums;

/**
 * The allergen vocabulary staff choose from. It is a fixed list so the wording is consistent and
 * guests can filter on it later, but WHICH allergens a dish contains is entirely the restaurant's
 * declaration: the platform never infers, defaults or guesses it.
 */
public enum Allergen {
    MILK("Milk"),
    EGG("Egg"),
    PEANUT("Peanut"),
    TREE_NUT("Tree nuts"),
    GLUTEN("Gluten (wheat)"),
    SOY("Soy"),
    FISH("Fish"),
    SHELLFISH("Shellfish"),
    SESAME("Sesame"),
    MUSTARD("Mustard");

    private final String label;

    Allergen(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
