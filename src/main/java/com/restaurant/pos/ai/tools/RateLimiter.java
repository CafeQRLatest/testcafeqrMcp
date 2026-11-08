package com.restaurant.pos.ai.tools;

import org.springframework.beans.factory.annotation.Autowired;
import com.restaurant.pos.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-café sliding one-minute window. In-memory, so it is per instance; move it to Redis when running more
 * than one instance of cafe-qr-ai.
 */
@Component
public class RateLimiter {

    private final int perMinute;
    private final Clock clock;
    private final Map<UUID, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(AiProperties properties) {
        this(properties.limits().requestsPerMinutePerClient(), Clock.systemUTC());
    }

    RateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** @return true if the call is allowed (and counts it). */
    public boolean tryAcquire(UUID clientId) {
        long now = clock.millis();
        Deque<Long> window = windows.computeIfAbsent(clientId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= now - 60_000) {
                window.pollFirst();
            }
            if (window.size() >= perMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
