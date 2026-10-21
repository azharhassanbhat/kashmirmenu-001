package com.kashmirmenu.app.concurrency;

import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Runs an action again when it fails for a reason that is worth a second try, a few times and no more.
 * Pure: the clock, the randomness and the notion of "worth retrying" are all passed in, so it can be
 * tested without a database or a Spring context.
 *
 * <p>Used for lock failures. When two requests collide on a row, MySQL may abort one of them (a
 * deadlock, or a lock wait that timed out). The abort rolls its transaction back completely, so running
 * the same operation again is safe and almost always succeeds, because the other request has finished.
 * It must wrap the whole transaction from OUTSIDE, never from inside it.
 */
public final class Retrying {

    private Retrying() {
    }

    /** Told what happened, so the caller can count it. */
    public interface Listener {
        /** An attempt failed and another will follow. */
        void onRetry(int attempt, RuntimeException cause);

        /** The last attempt failed too; the failure is about to be thrown. */
        void onGiveUp(RuntimeException cause);
    }

    /**
     * @param maxAttempts total tries, including the first; at least 1
     * @param baseDelayMs wait before the second try; each later wait grows by this much, plus a little jitter
     * @param sleeper     how to wait (injected so tests do not)
     * @param jitter      a value in [0, 1) that spreads colliding retries apart
     * @param retryable   whether a failure is worth another try; anything else is thrown immediately
     */
    public static <T> T call(
            int maxAttempts,
            long baseDelayMs,
            LongConsumer sleeper,
            DoubleSupplier jitter,
            Predicate<RuntimeException> retryable,
            Listener listener,
            Supplier<T> action) {

        int attempts = Math.max(1, maxAttempts);

        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!retryable.test(e)) {
                    throw e;
                }
                if (attempt >= attempts) {
                    listener.onGiveUp(e);
                    throw e;
                }
                listener.onRetry(attempt, e);
                sleeper.accept(delayFor(attempt, baseDelayMs, jitter.getAsDouble()));
            }
        }
    }

    /** The wait after the given failed attempt: grows with each, and the jitter keeps two retriers apart. */
    static long delayFor(int attempt, long baseDelayMs, double jitter) {
        return Math.round(baseDelayMs * attempt + baseDelayMs * Math.max(0.0, Math.min(jitter, 0.999)));
    }
}
