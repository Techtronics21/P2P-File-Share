package org.arnavthakur.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A small per-key fixed window rate limiter.
 *
 * The implementation relies on ConcurrentHashMap.compute so that updates for the
 * same key are serialized without adding broader locks.
 */
public class FixedWindowRateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowMs;

    private static class Window {
        private long windowStart;
        private int count;

        private Window(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }

    public FixedWindowRateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    public boolean allow(String key) {
        return allow(key, System.currentTimeMillis());
    }

    public boolean allow(String key, long nowMs) {
        AtomicBoolean allowed = new AtomicBoolean(false);

        windows.compute(key, (ignored, existingWindow) -> {
            if (existingWindow == null || nowMs - existingWindow.windowStart >= windowMs) {
                allowed.set(true);
                return new Window(nowMs, 1);
            }

            if (existingWindow.count >= limit) {
                allowed.set(false);
                return existingWindow;
            }

            existingWindow.count++;
            allowed.set(true);
            return existingWindow;
        });

        return allowed.get();
    }

    public int getLimit() {
        return limit;
    }
}
