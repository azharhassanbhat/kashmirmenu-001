package com.kashmirmenu.app.enums;

/**
 * How many options of a group a guest may pick. One generic model covers portion sizes (SINGLE,
 * required), add-ons (MULTI) and spice level (SINGLE), so there is one set of rules to get right.
 */
public enum OptionKind {
    /** At most one option; with {@code required}, exactly one. */
    SINGLE,
    /** Any number up to the group's maximum; with {@code required}, at least one. */
    MULTI
}
