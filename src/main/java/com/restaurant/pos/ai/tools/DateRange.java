package com.restaurant.pos.ai.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inclusive date range in the café's own timezone, converted to the UTC instants the backend expects.
 * Defaults: from = today, to = from.
 */
public record DateRange(LocalDate from, LocalDate to, ZoneId zone) {

    public static DateRange of(ToolArgs args, ZoneId zone, int maxDays, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = args.date("from");
        LocalDate to = args.date("to");
        if (from == null && to == null) {
            from = today;
            to = today;
        } else if (from == null) {
            from = to;
        } else if (to == null) {
            to = from;
        }
        if (from.isAfter(to)) {
            throw new ToolInputException("'from' (" + from + ") must not be after 'to' (" + to + ")");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maxDays) {
            throw new ToolInputException("Date range is " + days + " days; the maximum is " + maxDays + " days. Ask for a shorter period.");
        }
        return new DateRange(from, to, zone);
    }

    public Instant fromInstant() {
        return from.atStartOfDay(zone).toInstant();
    }

    /** Last millisecond of the 'to' day. */
    public Instant toInstant() {
        return to.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("timezone", zone.getId());
        return m;
    }
}
