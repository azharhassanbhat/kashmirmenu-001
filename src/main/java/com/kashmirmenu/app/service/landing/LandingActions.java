package com.kashmirmenu.app.service.landing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which action cards the landing page shows, and in what order. The owner stores a comma-separated list of keys:
 * a key in the list is shown, in that position; a key missing from it is hidden. No stored list means the default
 * order with everything switched on. An action whose own setting is missing (no review link, Wi-Fi off, ...) is
 * left out by the caller regardless of this list, so a card never leads nowhere.
 */
public final class LandingActions {

    /** The full set, in the default order. */
    public static final List<String> ALL = List.of("REWARDS", "REVIEW", "MENU", "WIFI", "FEEDBACK", "ASSISTANCE");

    private LandingActions() {
    }

    /** Valid keys only, upper-cased, no repeats, in the order given. Unknown keys are dropped, not an error. */
    public static List<String> normalise(List<String> keys) {
        Set<String> seen = new LinkedHashSet<>();
        if (keys != null) {
            for (String k : keys) {
                if (k == null) {
                    continue;
                }
                String key = k.strip().toUpperCase(Locale.ROOT);
                if (ALL.contains(key)) {
                    seen.add(key);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /** What to store: null (the default) when the list is the full default order, otherwise the csv. */
    public static String toStored(List<String> keys) {
        List<String> clean = normalise(keys);
        return clean.equals(ALL) ? null : String.join(",", clean);
    }

    /** The order to show for a stored value. Null or blank-from-never-set means everything, default order. */
    public static List<String> fromStored(String stored) {
        if (stored == null) {
            return ALL;
        }
        if (stored.isBlank()) {
            return List.of();
        }
        return normalise(List.of(stored.split(",")));
    }
}
