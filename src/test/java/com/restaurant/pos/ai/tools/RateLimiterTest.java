package com.restaurant.pos.ai.tools;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void limitsPerCafePerMinute() {
        AtomicLong now = new AtomicLong(Instant.parse("2026-11-06T10:00:00Z").toEpochMilli());
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        RateLimiter limiter = new RateLimiter(2, clock);
        UUID cafeA = UUID.randomUUID();
        UUID cafeB = UUID.randomUUID();

        assertThat(limiter.tryAcquire(cafeA)).isTrue();
        assertThat(limiter.tryAcquire(cafeA)).isTrue();
        assertThat(limiter.tryAcquire(cafeA)).isFalse();
        assertThat(limiter.tryAcquire(cafeB)).isTrue(); // other cafés are not affected

        now.addAndGet(60_001);
        assertThat(limiter.tryAcquire(cafeA)).isTrue();
    }
}
