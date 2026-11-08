package com.restaurant.pos.ai.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/** Typed, validated access to tool arguments. Invalid input raises {@link ToolInputException}. */
public final class ToolArgs {

    private final Map<String, Object> values;

    public ToolArgs(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    public String string(String name) {
        Object v = values.get(name);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    public LocalDate date(String name) {
        String s = string(name);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new ToolInputException("'" + name + "' must be a date in YYYY-MM-DD format, got '" + s + "'");
        }
    }

    public UUID uuid(String name) {
        String s = string(name);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new ToolInputException("'" + name + "' must be an ID returned by list_branches, got '" + s + "'");
        }
    }

    public int integer(String name, int defaultValue, int min, int max) {
        Object v = values.get(name);
        if (v == null || v.toString().isBlank()) {
            return defaultValue;
        }
        try {
            int i = v instanceof Number n ? n.intValue() : (int) Double.parseDouble(v.toString());
            return Math.max(min, Math.min(max, i));
        } catch (NumberFormatException e) {
            throw new ToolInputException("'" + name + "' must be a number, got '" + v + "'");
        }
    }

    public boolean bool(String name, boolean defaultValue) {
        Object v = values.get(name);
        if (v == null) {
            return defaultValue;
        }
        return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
    }
}
