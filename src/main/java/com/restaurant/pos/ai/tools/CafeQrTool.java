package com.restaurant.pos.ai.tools;

import com.restaurant.pos.ai.security.OwnerPrincipal;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * One read-only CafeQR capability, defined once and exposed both to the built-in chat (Spring AI tool callback)
 * and to external AI apps (MCP tool).
 *
 * @param inputSchema JSON Schema (object) of the arguments
 * @param handler     runs the tool for the given owner; returns a JSON-serialisable result
 */
public record CafeQrTool(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        BiFunction<OwnerPrincipal, ToolArgs, Object> handler) {
}
