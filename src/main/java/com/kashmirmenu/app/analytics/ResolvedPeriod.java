package com.kashmirmenu.app.analytics;

/**
 * The selected period plus the comparable previous period. {@code previous} covers the same elapsed
 * time in the preceding cycle, so "today so far" is compared with yesterday up to the same time of day
 * instead of with all of yesterday.
 */
public record ResolvedPeriod(PeriodPreset preset, DateRange current, DateRange previous) {
}
