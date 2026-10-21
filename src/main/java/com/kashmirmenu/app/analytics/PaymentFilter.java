package com.kashmirmenu.app.analytics;

import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Filters shared by every analytics query so all panels of the dashboard agree. Provider filtering
 * understands the "Unknown" bucket: {@code unknownProviderOf} lists the parent methods (UPI / WALLET)
 * whose payments with no recorded provider should be included.
 */
public record PaymentFilter(
        Long restaurantId,
        Set<PaymentMethod> methods,
        Set<PaymentProvider> providers,
        Set<PaymentMethod> unknownProviderOf,
        Set<PaymentStatus> statuses,
        Long staffId) {

    public static final String UNKNOWN_UPI = AnalyticsCalculator.UNKNOWN_UPI;
    public static final String UNKNOWN_WALLET = AnalyticsCalculator.UNKNOWN_WALLET;

    public static PaymentFilter of(
            Long restaurantId,
            Collection<PaymentMethod> methods,
            Collection<String> providerKeys,
            Collection<PaymentStatus> statuses,
            Long staffId) {

        Set<PaymentProvider> providers = EnumSet.noneOf(PaymentProvider.class);
        Set<PaymentMethod> unknown = EnumSet.noneOf(PaymentMethod.class);
        if (providerKeys != null) {
            for (String key : providerKeys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (UNKNOWN_UPI.equals(key)) {
                    unknown.add(PaymentMethod.UPI);
                } else if (UNKNOWN_WALLET.equals(key)) {
                    unknown.add(PaymentMethod.WALLET);
                } else {
                    try {
                        providers.add(PaymentProvider.valueOf(key));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown payment provider: " + key);
                    }
                }
            }
        }
        return new PaymentFilter(
                restaurantId,
                methods == null || methods.isEmpty() ? EnumSet.noneOf(PaymentMethod.class) : EnumSet.copyOf(methods),
                providers,
                unknown,
                statuses == null || statuses.isEmpty() ? EnumSet.noneOf(PaymentStatus.class) : EnumSet.copyOf(statuses),
                staffId);
    }

    public boolean hasProviderFilter() {
        return !providers.isEmpty() || !unknownProviderOf.isEmpty();
    }

    /** True when anything other than the restaurant narrows the payments (orders cannot be narrowed this way). */
    public boolean narrowsPayments() {
        return !methods.isEmpty() || hasProviderFilter() || !statuses.isEmpty() || staffId != null;
    }

    /** The statuses that count as received money under this filter. Empty = nothing can match. */
    public Set<PaymentStatus> capturedStatuses() {
        return intersect(PaymentStatus.CAPTURED);
    }

    /** The statuses that are in flight or unsuccessful under this filter. */
    public Set<PaymentStatus> openStatuses() {
        return intersect(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.FAILED, PaymentStatus.CANCELLED));
    }

    /** Refunds belong to payments that were received; they are shown unless the status filter excludes those. */
    public boolean includesRefunds() {
        return statuses.isEmpty()
                || statuses.contains(PaymentStatus.PARTIALLY_REFUNDED)
                || statuses.contains(PaymentStatus.REFUNDED);
    }

    private Set<PaymentStatus> intersect(Set<PaymentStatus> base) {
        Set<PaymentStatus> out = EnumSet.noneOf(PaymentStatus.class);
        for (PaymentStatus s : base) {
            if (statuses.isEmpty() || statuses.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
