package com.kashmirmenu.app.concurrency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryingTest {

    /** The kind of failure worth another try. */
    private static final class Busy extends RuntimeException {
        Busy(String m) {
            super(m);
        }
    }

    private static final class Broken extends RuntimeException {
        Broken(String m) {
            super(m);
        }
    }

    private static final class Recorder implements Retrying.Listener {
        final List<Integer> retriedAfter = new ArrayList<>();
        final List<RuntimeException> gaveUpOn = new ArrayList<>();

        @Override
        public void onRetry(int attempt, RuntimeException cause) {
            retriedAfter.add(attempt);
        }

        @Override
        public void onGiveUp(RuntimeException cause) {
            gaveUpOn.add(cause);
        }
    }

    private final List<Long> slept = new ArrayList<>();

    private <T> T run(int attempts, Recorder rec, java.util.function.Supplier<T> action) {
        return Retrying.call(attempts, 25, slept::add, () -> 0.0, e -> e instanceof Busy, rec, action);
    }

    @Test
    void anActionThatSucceedsRunsOnceAndWaitsForNothing() {
        Recorder rec = new Recorder();
        AtomicInteger calls = new AtomicInteger();

        String result = run(3, rec, () -> {
            calls.incrementAndGet();
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, calls.get());
        assertTrue(slept.isEmpty());
        assertTrue(rec.retriedAfter.isEmpty());
        assertTrue(rec.gaveUpOn.isEmpty());
    }

    @Test
    void aRetryableFailureIsTriedAgainUntilItWorks() {
        Recorder rec = new Recorder();
        AtomicInteger calls = new AtomicInteger();

        String result = run(3, rec, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new Busy("deadlock");
            }
            return "worked on the third go";
        });

        assertEquals("worked on the third go", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(1, 2), rec.retriedAfter);
        assertTrue(rec.gaveUpOn.isEmpty());
    }

    @Test
    void itGivesUpAfterTheLastAttemptAndThrowsTheLastFailure() {
        Recorder rec = new Recorder();
        AtomicInteger calls = new AtomicInteger();

        Busy thrown = assertThrows(Busy.class, () -> run(3, rec, () -> {
            throw new Busy("attempt " + calls.incrementAndGet());
        }));

        assertEquals("attempt 3", thrown.getMessage(), "the last failure is the one reported");
        assertEquals(3, calls.get(), "no more than the allowed attempts");
        assertEquals(List.of(1, 2), rec.retriedAfter);
        assertEquals(1, rec.gaveUpOn.size());
        assertSame(thrown, rec.gaveUpOn.get(0));
    }

    @Test
    void aFailureNotWorthRetryingIsThrownAtOnce() {
        Recorder rec = new Recorder();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(Broken.class, () -> run(3, rec, () -> {
            calls.incrementAndGet();
            throw new Broken("a real bug");
        }));

        assertEquals(1, calls.get(), "a real error must not be retried");
        assertTrue(slept.isEmpty());
        assertTrue(rec.retriedAfter.isEmpty());
        assertTrue(rec.gaveUpOn.isEmpty());
    }

    @Test
    void theWaitGrowsWithEachFailedAttempt() {
        Recorder rec = new Recorder();

        assertThrows(Busy.class, () -> run(4, rec, () -> {
            throw new Busy("x");
        }));

        // Jitter is pinned to zero here: 25, 50, 75 after the first, second and third failures.
        assertEquals(List.of(25L, 50L, 75L), slept);
    }

    @Test
    void jitterSpreadsTwoCollidingRetriesApart() {
        assertEquals(25L, Retrying.delayFor(1, 25, 0.0));
        assertTrue(Retrying.delayFor(1, 25, 0.9) > Retrying.delayFor(1, 25, 0.1));
        // Never more than one extra base delay (so 200 at most for a base of 100), whatever the jitter
        // source returns.
        assertTrue(Retrying.delayFor(1, 100, 5.0) <= 200);
        assertTrue(Retrying.delayFor(1, 100, -3.0) >= 100);
    }

    @Test
    void fewerThanOneAttemptStillRunsOnce() {
        Recorder rec = new Recorder();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(Busy.class, () -> run(0, rec, () -> {
            calls.incrementAndGet();
            throw new Busy("x");
        }));

        assertEquals(1, calls.get());
    }
}
