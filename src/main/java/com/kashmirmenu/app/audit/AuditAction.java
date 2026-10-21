package com.kashmirmenu.app.audit;

/**
 * The security-relevant actions worth a permanent record. Kept as an enum so the set is visible in
 * one place and the audit viewer can offer it as a filter; stored as its name.
 */
public enum AuditAction {

    LOGIN_SUCCEEDED("Signed in"),
    LOGIN_FAILED("Failed sign-in attempt"),
    LOGGED_OUT("Signed out"),

    PLATFORM_OWNER_BOOTSTRAPPED("Platform owner account created, awaiting claim"),
    PLATFORM_OWNER_CLAIMED("Platform owner account claimed"),
    PLATFORM_OWNER_CLAIM_FAILED("Failed platform owner claim attempt"),

    RESTAURANT_REGISTERED("Restaurant registered, awaiting approval"),
    RESTAURANT_APPROVED("Restaurant approved"),
    RESTAURANT_REJECTED("Restaurant rejected"),
    RESTAURANT_SUSPENDED("Restaurant suspended"),
    RESTAURANT_REACTIVATED("Restaurant reactivated"),

    USER_SUSPENDED("User account suspended"),
    USER_REACTIVATED("User account reactivated"),
    TABLE_SESSION_CLOSED("Table closed"),
    TABLE_SESSION_REOPENED("Table reopened"),
    TABLE_SESSION_EXPIRED("Table closed automatically after being idle"),
    STAFF_CREATED("Staff account created"),
    STAFF_UPDATED("Staff account changed"),
    STAFF_DEACTIVATED("Staff account deactivated"),
    STAFF_REACTIVATED("Staff account reactivated"),
    STAFF_PASSWORD_RESET("Staff password reset"),
    MENU_IMPORTED("Menu imported from a file"),

    PAYMENT_COLLECTED("Payment recorded by staff"),
    PAYMENT_STATUS_CHANGED("Payment status changed by staff"),
    PAYMENT_REFUNDED("Refund recorded by staff"),
    PAYMENT_SETTINGS_CHANGED("Payment settings changed");

    private final String label;

    AuditAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True for actions that represent a refused or failed attempt, which the viewer highlights. */
    public boolean isFailure() {
        return this == LOGIN_FAILED || this == PLATFORM_OWNER_CLAIM_FAILED;
    }
}
