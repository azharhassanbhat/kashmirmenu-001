package com.kashmirmenu.app.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptTrackerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private LoginAttemptTracker tracker(int limit, int windowSeconds) {
        return new LoginAttemptTracker(limit, windowSeconds, now::get);
    }

    @Test
    void locksOnlyAfterTheLimitOfFailuresAndLeavesOtherAccountsAlone() {
        LoginAttemptTracker t = tracker(3, 60);
        String victim = LoginAttemptTracker.keyFor("owner@example.com");
        for (int i = 0; i < 2; i++) {
            t.recordFailure(victim);
            assertFalse(t.isLocked(victim), "still under the limit after " + (i + 1));
        }
        t.recordFailure(victim);
        assertTrue(t.isLocked(victim));
        assertFalse(t.isLocked(LoginAttemptTracker.keyFor("someone.else@example.com")));
    }

    @Test
    void checkingDoesNotCount() {
        LoginAttemptTracker t = tracker(2, 60);
        String key = LoginAttemptTracker.keyFor("a@example.com");
        for (int i = 0; i < 50; i++) {
            assertFalse(t.isLocked(key));
        }
        t.recordFailure(key);
        assertFalse(t.isLocked(key));
    }

    @Test
    void theWindowRollsOver() {
        LoginAttemptTracker t = tracker(2, 60);
        String key = LoginAttemptTracker.keyFor("a@example.com");
        t.recordFailure(key);
        t.recordFailure(key);
        assertTrue(t.isLocked(key));
        now.addAndGet(61_000);
        assertFalse(t.isLocked(key), "a new window starts clean");
        t.recordFailure(key);
        assertFalse(t.isLocked(key));
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        LoginAttemptTracker t = tracker(2, 60);
        String key = LoginAttemptTracker.keyFor("a@example.com");
        t.recordFailure(key);
        t.reset(key);
        t.recordFailure(key);
        assertFalse(t.isLocked(key));
    }

    @Test
    void caseAndSpacesDoNotGiveFreshAttempts() {
        assertEquals(LoginAttemptTracker.keyFor("Owner@Example.com"), LoginAttemptTracker.keyFor("  owner@example.COM "));
        assertNotEquals(LoginAttemptTracker.keyFor("a@example.com"), LoginAttemptTracker.keyFor("b@example.com"));
        assertFalse(LoginAttemptTracker.keyFor("owner@example.com").contains("owner"), "the map holds a hash, not the address");
    }

    @Test
    void theMapCannotGrowWithoutBound() {
        LoginAttemptTracker t = tracker(2, 60);
        for (int i = 0; i < LoginAttemptTracker.MAX_KEYS + 10; i++) {
            t.recordFailure("k" + i);
        }
        assertTrue(t.size() <= LoginAttemptTracker.MAX_KEYS + 1);
    }
}
