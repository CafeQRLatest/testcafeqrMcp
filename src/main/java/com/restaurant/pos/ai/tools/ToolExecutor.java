package com.restaurant.pos.ai.tools;

import com.restaurant.pos.ai.backend.BackendException;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs a {@link CafeQrTool} for an owner: rate limit, error mapping and an audit log line
 * (tool, café, user, channel, duration, outcome — never the response data).
 */
@Component
public class ToolExecutor {

    private static final Logger audit = LoggerFactory.getLogger("cafeqr.ai.audit");

    public enum Channel { CHAT, MCP }

    /** Outcome as JSON text for the model, plus whether it failed. */
    public record Result(String json, boolean error) {
    }

    private final Map<String, CafeQrTool> tools;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ToolExecutor(CafeQrTools catalogue, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.tools = catalogue.all().stream().collect(Collectors.toMap(CafeQrTool::name, Function.identity()));
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    public List<CafeQrTool> tools() {
        return List.copyOf(tools.values());
    }

    public Result execute(String toolName, Map<String, Object> arguments, OwnerPrincipal owner, Channel channel) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            CafeQrTool tool = tools.get(toolName);
            if (tool == null) {
                outcome = "unknown_tool";
                return error("Unknown tool '" + toolName + "'.");
            }
            if (!rateLimiter.tryAcquire(owner.clientId())) {
                outcome = "rate_limited";
                return error("Too many requests right now. Please wait a minute and try again.");
            }
            Object result = tool.handler().apply(owner, new ToolArgs(arguments));
            return new Result(objectMapper.writeValueAsString(result), false);
        } catch (ToolInputException e) {
            outcome = "bad_input";
            return error(e.getMessage());
        } catch (BackendException e) {
            outcome = "backend_" + e.status();
            return error(e.getMessage());
        } catch (Exception e) {
            outcome = "error";
            LoggerFactory.getLogger(ToolExecutor.class).error("Tool {} failed for {}", toolName, owner, e);
            return error("Something went wrong while fetching this data. Please try again.");
        } finally {
            audit.info("tool={} channel={} clientId={} userId={} outcome={} ms={}", toolName, channel,
                    owner.clientId(), owner.userId(), outcome, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private Result error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return new Result(objectMapper.writeValueAsString(body), true);
    }
}
