package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.exception.AccessDeniedException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who may create and change which staff accounts, and what a staff password must look like. Pure
 * rules with no database, so the permissions that matter most are testable on their own.
 *
 * <p>The principle: nobody can hand out more power than they hold, and nobody can touch an account
 * above them. An owner (or restaurant admin) may manage managers and every floor and kitchen role;
 * a manager may manage only chefs, waiters and cashiers. Owners, admins, platform owners and diners
 * are never created or changed here.
 */
public final class StaffRules {

    /** Roles that can be given to a staff account through the product at all. */
    public static final Set<RoleName> ASSIGNABLE = Collections.unmodifiableSet(
            EnumSet.of(RoleName.MANAGER, RoleName.CHEF, RoleName.WAITER, RoleName.CASHIER));

    /** What a manager may manage: the floor and the kitchen, not other managers. */
    private static final Set<RoleName> MANAGER_MAY = Collections.unmodifiableSet(
            EnumSet.of(RoleName.CHEF, RoleName.WAITER, RoleName.CASHIER));

    public static final int MIN_PASSWORD = 8;
    public static final int MAX_PASSWORD = 100;

    private StaffRules() {
    }

    /** Whether {@code actor} may create an account with, or change an account that has, the role {@code target}. */
    public static boolean canManage(RoleName actor, RoleName target) {
        if (actor == null || target == null) {
            return false;
        }
        return switch (actor) {
            case RESTAURANT_OWNER, ADMIN -> ASSIGNABLE.contains(target);
            case MANAGER -> MANAGER_MAY.contains(target);
            default -> false;
        };
    }

    /** Refuses (403) a role the actor may not hand out. */
    public static void requireCanAssign(RoleName actor, RoleName role) {
        if (!canManage(actor, role)) {
            throw new AccessDeniedException("You are not allowed to give the " + label(role) + " role.");
        }
    }

    /**
     * What is wrong with a staff password, as a sentence for the person setting it, or null when it
     * is acceptable. Long enough to resist guessing, containing a letter and a digit, and not the
     * email address or name it belongs to.
     */
    public static String passwordProblem(String password, String email, String name) {
        if (password == null || password.isBlank()) {
            return "Please enter a password.";
        }
        if (password.length() < MIN_PASSWORD) {
            return "The password must be at least " + MIN_PASSWORD + " characters.";
        }
        if (password.length() > MAX_PASSWORD) {
            return "The password must be at most " + MAX_PASSWORD + " characters.";
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            return "The password must contain at least one letter and one number.";
        }
        String lowered = password.toLowerCase(Locale.ROOT);
        if (email != null && !email.isBlank()) {
            String e = email.toLowerCase(Locale.ROOT).strip();
            if (lowered.equals(e) || lowered.equals(e.split("@")[0])) {
                return "The password must not be the same as the email address.";
            }
        }
        if (name != null && !name.isBlank() && lowered.equals(name.toLowerCase(Locale.ROOT).strip())) {
            return "The password must not be the same as the name.";
        }
        return null;
    }

    private static String label(RoleName role) {
        return role == null ? "that" : role.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
