package com.kashmirmenu.app.security.filter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * An in-memory fixed-window counter per key: at most {@code limit} calls in each window, then refused
 * until the window rolls over. No Spring and no servlet types, so it can be tested directly with a fake
 * clock.
 *
 * <p>Per instance, not shared: behind several servers each keeps its own count. That is acceptable as a
 * blunt first line of defence, not as a substitute for a limit at the edge. The key map is capped, and
 * simply cleared if it ever grows past that, so a flood of invented keys cannot exhaust memory.
 */
public final class FixedWindowLimiter {

    static final int MAX_KEYS = 50_000;

    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowLimiter() {
        this(System::currentTimeMillis);
    }

    FixedWindowLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Counts one call for the key. True if it is within the limit, false if it should be refused. */
    public boolean tryConsume(String key, int limit, int windowSeconds) {
        if (windows.size() > MAX_KEYS) {
            windows.clear();
        }
        return windows.computeIfAbsent(key, k -> new Window())
                .tryConsume(limit, windowSeconds, clockMillis.getAsLong());
    }

    int size() {
        return windows.size();
    }

    private static final class Window {
        private long start = -1;
        private int count;

        synchronized boolean tryConsume(int limit, int windowSeconds, long now) {
            if (start < 0 || now > start + windowSeconds * 1000L) {
                start = now;
                count = 0;
            }
            return ++count <= limit;
        }
    }
}
