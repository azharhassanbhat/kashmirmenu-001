package com.kashmirmenu.app.concurrency;

import com.kashmirmenu.app.metrics.FloorMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Wraps a guest or staff action in a short, bounded retry on lock failures (deadlocks and lock wait
 * timeouts). See {@link Retrying} for why this is safe, and the rule that it must go OUTSIDE the
 * transaction: put it around the call to a @Transactional method, not inside one.
 *
 * <p>Three tries, waiting a few tens of milliseconds between them. If they all fail the exception is
 * thrown and the API answers 503 "busy, try again", which the screens present as such.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_DELAY_MS = 25;

    private final FloorMetrics metrics;

    public <T> T call(String operation, Supplier<T> action) {
        return Retrying.call(
                MAX_ATTEMPTS,
                BASE_DELAY_MS,
                LockRetry::sleep,
                Math::random,
                e -> e instanceof PessimisticLockingFailureException,
                new Retrying.Listener() {
                    @Override
                    public void onRetry(int attempt, RuntimeException cause) {
                        metrics.lockRetried(operation);
                        log.info("Lock conflict in {} (attempt {}), retrying: {}", operation, attempt, cause.getClass().getSimpleName());
                    }

                    @Override
                    public void onGiveUp(RuntimeException cause) {
                        metrics.lockGaveUp(operation);
                        log.warn("Lock conflict in {} on every attempt: {}", operation, cause.getClass().getSimpleName());
                    }
                },
                action);
    }

    public void run(String operation, Runnable action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
