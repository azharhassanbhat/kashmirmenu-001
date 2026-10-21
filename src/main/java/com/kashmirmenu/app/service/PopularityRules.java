package com.kashmirmenu.app.service;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Which dishes count as popular, given units sold. Pure, so the rule is easy to test. */
public final class PopularityRules {

    /** A dish must have sold at least this many units before it can be called popular. */
    public static final int MIN_UNITS = 5;
    /** At most this many dishes carry the badge. */
    public static final int TOP_N = 3;

    private PopularityRules() {
    }

    /** The top sellers with at least {@link #MIN_UNITS} units; ties go to the lower id, so it is deterministic. */
    public static Set<Long> pick(Map<Long, Long> unitsByDish) {
        return unitsByDish.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= MIN_UNITS)
                .sorted(Comparator
                        .comparing((Map.Entry<Long, Long> e) -> e.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_N)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
