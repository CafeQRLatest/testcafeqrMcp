package com.restaurant.pos.ai.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny builder for the JSON Schema objects that describe tool arguments. */
final class Schema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    static Schema object() {
        return new Schema();
    }

    Schema string(String name, String description) {
        properties.put(name, Map.of("type", "string", "description", description));
        return this;
    }

    Schema date(String name, String description) {
        properties.put(name, Map.of("type", "string", "format", "date", "description", description));
        return this;
    }

    Schema integer(String name, String description, int min, int max) {
        properties.put(name, Map.of("type", "integer", "description", description, "minimum", min, "maximum", max));
        return this;
    }

    Schema bool(String name, String description) {
        properties.put(name, Map.of("type", "boolean", "description", description));
        return this;
    }

    Schema enumeration(String name, String description, List<String> values) {
        properties.put(name, Map.of("type", "string", "description", description, "enum", values));
        return this;
    }

    Schema dateRange() {
        return date("from", "First day, YYYY-MM-DD, in the café's timezone. Defaults to today.")
                .date("to", "Last day (inclusive), YYYY-MM-DD. Defaults to 'from'.");
    }

    Schema branch() {
        return string("branch_id", "Optional branch ID from list_branches. Omit for the user's current branch.");
    }

    Schema require(String... names) {
        required.addAll(List.of(names));
        return this;
    }

    Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }
}
