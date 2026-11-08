package com.restaurant.pos.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * cafeqr.ai.* settings. Secrets (API keys, public key) come from environment variables only.
 */
@ConfigurationProperties(prefix = "cafeqr.ai")
public record AiProperties(
        Backend backend,
        Security security,
        Limits limits,
        Assistant assistant) {

    /** cafe-qr-backend REST API that every tool calls with the owner's own ai:read token. */
    public record Backend(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
    }

    /**
     * @param jwtPublicKey   Base64 X.509 RSA public key of cafe-qr-backend. When blank it is fetched from
     *                       {backend}/api/v1/public/ai/jwt-public-key on first use.
     * @param allowedRoles   roles allowed to use the assistant (owners and managers).
     * @param allowedOrigins browser origins allowed to call the chat API (owner dashboard).
     */
    public record Security(String jwtPublicKey, List<String> allowedRoles, List<String> allowedOrigins) {
    }

    /**
     * @param monthlyQuestionsPerClient built-in chat questions per café per calendar month (we pay for these).
     * @param requestsPerMinutePerClient chat + MCP tool calls per café per minute.
     * @param maxRows                    max rows a tool returns, to keep answers small and cheap.
     * @param maxRangeDays               max report date range.
     */
    public record Limits(int monthlyQuestionsPerClient, int requestsPerMinutePerClient, int maxRows, int maxRangeDays) {
    }

    /**
     * @param historyMessages previous messages sent to the model for context.
     * @param maxMessageChars longest question accepted.
     * @param model           model label recorded in usage rows (the provider is chosen by spring.ai.model.chat).
     */
    public record Assistant(int historyMessages, int maxMessageChars, String model) {
    }
}
