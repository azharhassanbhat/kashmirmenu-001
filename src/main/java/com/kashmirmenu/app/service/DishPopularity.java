package com.kashmirmenu.app.service;

import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.repository.OrderItemRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which dishes get the "Popular" badge: the best sellers over the last 30 days of REAL orders,
 * cancelled ones excluded. A dish needs a minimum number of units sold before it can be called
 * popular, so a new restaurant with three orders does not label everything.
 *
 * <p>Cached for a few minutes per restaurant because the menu is read far more often than orders
 * arrive. This is safe in a way price caching would not be: the badge is decoration and carries no
 * money, so a stale entry can only be a few minutes out of date.
 */
@Service
public class DishPopularity {

    static final int WINDOW_DAYS = 30;
    private static final Duration TTL = Duration.ofMinutes(5);

    private record Cached(Set<Long> ids, long expiresAtMillis) {
    }

    private final OrderItemRepository orderItems;
    private final BusinessTime businessTime;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public DishPopularity(OrderItemRepository orderItems, BusinessTime businessTime) {
        this.orderItems = orderItems;
        this.businessTime = businessTime;
    }

    public Set<Long> popularIds(Long restaurantId) {
        long now = System.currentTimeMillis();
        Cached hit = cache.get(restaurantId);
        if (hit != null && hit.expiresAtMillis() > now) {
            return hit.ids();
        }

        Map<Long, Long> sold = new HashMap<>();
        for (Object[] row : orderItems.unitsSoldSince(
                restaurantId, businessTime.now().minusDays(WINDOW_DAYS))) {
            sold.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        Set<Long> ids = PopularityRules.pick(sold);
        cache.put(restaurantId, new Cached(ids, now + TTL.toMillis()));
        return ids;
    }
}
