package com.kashmirmenu.app.service;

import java.util.ArrayList;
import java.util.List;

/**
 * How far a new restaurant has got towards taking real orders, worked out from what actually exists in
 * its data. Pure, so the rules are testable. Nothing here is stored or self-reported: a step is done
 * because the thing is there (a category, a dish, a table), never because someone ticked a box, so the
 * checklist cannot drift out of step with the restaurant.
 *
 * <p>Steps that cannot be verified from data (printing the QR cards, reviewing tax settings) are left
 * out rather than guessed at.
 */
public final class OnboardingRules {

    private OnboardingRules() {
    }

    /** What exists for the restaurant. Counts are of usable things (active categories, dishes guests can order, active tables). */
    public record Counts(long categories, long orderableDishes, long tables, boolean paymentConfigured,
                         long orders, long teamMembers) {
    }

    /**
     * @param key      stable identifier for the screen
     * @param route    the admin page where the step is done
     * @param required whether the restaurant is ready to go live without it
     */
    public record Step(String key, String title, String hint, String route, boolean done, boolean required) {
    }

    /**
     * @param complete  every REQUIRED step is done
     * @param nextKey   the first required step still to do, or null
     * @param percent   share of REQUIRED steps done, 0 to 100
     */
    public record Progress(List<Step> steps, int requiredDone, int requiredTotal, int percent,
                           boolean complete, String nextKey) {
    }

    public static Progress evaluate(Counts c) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("categories", "Create a menu category",
                "Group your dishes, for example Starters, Mains and Drinks.",
                "/admin/categories", c.categories() > 0, true));
        steps.add(new Step("dishes", "Add your dishes",
                "Add them one by one, or import a whole menu from a spreadsheet.",
                "/admin/menu", c.orderableDishes() > 0, true));
        steps.add(new Step("tables", "Add your tables",
                "Each table gets its own QR code to print and place on it.",
                "/admin/tables", c.tables() > 0, true));
        steps.add(new Step("payments", "Choose how guests can pay",
                "Turn on cash, UPI or card so guests can settle their bill.",
                "/admin/payments", c.paymentConfigured(), true));
        steps.add(new Step("testOrder", "Place a test order",
                "Scan a table's QR code on your phone and order a dish, so you see what guests will see.",
                "/admin/tables", c.orders() > 0, true));
        steps.add(new Step("team", "Add your team",
                "Give each waiter, chef and cashier their own sign-in. You can do this later.",
                "/admin/staff", c.teamMembers() > 0, false));

        int total = 0, done = 0;
        String next = null;
        for (Step s : steps) {
            if (!s.required()) {
                continue;
            }
            total++;
            if (s.done()) {
                done++;
            } else if (next == null) {
                next = s.key();
            }
        }
        int percent = total == 0 ? 100 : (int) Math.round(done * 100.0 / total);
        return new Progress(List.copyOf(steps), done, total, percent, done == total, next);
    }
}
