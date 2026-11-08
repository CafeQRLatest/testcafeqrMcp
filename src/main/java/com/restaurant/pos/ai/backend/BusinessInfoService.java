package com.restaurant.pos.ai.backend;

import org.springframework.beans.factory.annotation.Autowired;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Café name, currency, timezone and country as configured in CafeQR (GET /api/v1/clients/me), cached briefly.
 * Currency and timezone always come from the café's own settings; nothing is assumed per country.
 */
@Service
public class BusinessInfoService {

    private static final Duration TTL = Duration.ofMinutes(10);

    public record BusinessInfo(String name, String currency, String timezone, String country) {
        public ZoneId zone() {
            try {
                return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
            } catch (DateTimeException e) {
                return ZoneOffset.UTC;
            }
        }
    }

    private record Cached(BusinessInfo info, Instant loadedAt) {
    }

    private final CafeQrApiClient api;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public BusinessInfoService(CafeQrApiClient api) {
        this(api, Clock.systemUTC());
    }

    BusinessInfoService(CafeQrApiClient api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    public BusinessInfo get(OwnerPrincipal owner) {
        Cached cached = cache.get(owner.clientId());
        Instant now = clock.instant();
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(now)) {
            return cached.info();
        }
        JsonNode c = api.get(owner, "/api/v1/clients/me", Map.of());
        BusinessInfo info = new BusinessInfo(
                text(c, "name"), text(c, "currency"), text(c, "timezone"), text(c, "country"));
        cache.put(owner.clientId(), new Cached(info, now));
        return info;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }
}
