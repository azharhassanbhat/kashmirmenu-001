package com.kashmirmenu.app.security.filter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedWindowLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final FixedWindowLimiter limiter = new FixedWindowLimiter(now::get);

    @Test
    void allowsUpToTheLimitThenRefuses() {
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryConsume("a", 3, 60), "call " + (i + 1) + " is within the limit");
        }
        assertFalse(limiter.tryConsume("a", 3, 60), "the fourth is over it");
        assertFalse(limiter.tryConsume("a", 3, 60), "and stays refused for the rest of the window");
    }

    @Test
    void theAllowanceComesBackWhenTheWindowRollsOver() {
        assertTrue(limiter.tryConsume("a", 1, 60));
        assertFalse(limiter.tryConsume("a", 1, 60));

        now.addAndGet(59_000);
        assertFalse(limiter.tryConsume("a", 1, 60), "still inside the window");

        now.addAndGet(2_000);
        assertTrue(limiter.tryConsume("a", 1, 60), "a new window has begun");
    }

    @Test
    void eachKeyHasItsOwnAllowance() {
        assertTrue(limiter.tryConsume("guest:one", 1, 60));
        assertFalse(limiter.tryConsume("guest:one", 1, 60));

        // Another guest at the same table, and the same address, is unaffected.
        assertTrue(limiter.tryConsume("guest:two", 1, 60));
    }

    @Test
    void aBusyGuestUsesUpTheirOwnAllowanceAndNotTheAddressCeiling() {
        // The way the filter uses it: a high ceiling per address, a low limit per guest.
        int ceiling = 600;
        int perGuest = 5;

        for (int i = 0; i < perGuest; i++) {
            assertTrue(limiter.tryConsume("public:10.0.0.1", ceiling, 60));
            assertTrue(limiter.tryConsume("guest:busy", perGuest, 60));
        }
        // The busy guest is refused...
        assertTrue(limiter.tryConsume("public:10.0.0.1", ceiling, 60));
        assertFalse(limiter.tryConsume("guest:busy", perGuest, 60));

        // ...while a quiet guest behind the same address still gets through.
        assertTrue(limiter.tryConsume("public:10.0.0.1", ceiling, 60));
        assertTrue(limiter.tryConsume("guest:quiet", perGuest, 60));
    }

    @Test
    void aFloodOfInventedKeysCannotGrowTheMapWithoutBound() {
        for (int i = 0; i <= FixedWindowLimiter.MAX_KEYS + 10; i++) {
            limiter.tryConsume("k" + i, 1, 60);
        }
        assertTrue(limiter.size() <= FixedWindowLimiter.MAX_KEYS + 10,
                "the map was cleared once it passed its cap instead of growing for ever");
        assertTrue(limiter.size() < FixedWindowLimiter.MAX_KEYS, "and is far smaller than the number of keys seen");
    }

    @Test
    void aLimitOfZeroRefusesEverything() {
        assertFalse(limiter.tryConsume("a", 0, 60));
        assertEquals(1, limiter.size());
    }
}
