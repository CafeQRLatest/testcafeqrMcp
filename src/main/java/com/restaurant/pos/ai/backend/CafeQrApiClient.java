package com.restaurant.pos.ai.backend;

import com.restaurant.pos.ai.config.AiProperties;
import com.restaurant.pos.ai.security.OwnerPrincipal;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Read-only client for cafe-qr-backend. Every call carries the owner's own ai:read token, so tenant isolation
 * and role checks are done by the backend exactly as for the dashboard. Only GET is exposed on purpose.
 */
@Component
public class CafeQrApiClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CafeQrApiClient(AiProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        AiProperties.Backend backend = properties.backend();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(backend.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(backend.readTimeoutMs()));
        this.restClient = builder.clone()
                .baseUrl(backend.baseUrl())
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * GETs {@code path} and returns the {@code data} field of the backend's ApiResponse wrapper.
     * Null query values are skipped.
     */
    public JsonNode get(OwnerPrincipal owner, String path, Map<String, ?> query) {
        try {
            String body = restClient.get()
                    .uri(b -> buildUri(b, path, query))
                    .header("Authorization", "Bearer " + owner.token())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw toException(res.getStatusCode().value(), new String(res.getBody().readAllBytes()));
                    })
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            if (root.has("success") && !root.path("success").asBoolean(true)) {
                throw new BackendException(400, message(root, "The request could not be completed."));
            }
            return root.has("data") ? root.get("data") : root;
        } catch (BackendException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new BackendException(503, "CafeQR is not reachable right now. Please try again in a moment.");
        } catch (RestClientResponseException e) {
            throw toException(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    private static URI buildUri(UriBuilder builder, String path, Map<String, ?> query) {
        builder.path(path);
        if (query != null) {
            query.forEach((k, v) -> {
                if (v != null) {
                    builder.queryParam(k, v.toString());
                }
            });
        }
        return builder.build();
    }

    private BackendException toException(int status, String body) {
        String detail = null;
        try {
            detail = message(objectMapper.readTree(body), null);
        } catch (Exception ignored) {
            // non-JSON error body
        }
        return switch (status) {
            case 401 -> new BackendException(401, "Your CafeQR session has expired. Please sign in again.");
            case 402 -> new BackendException(402, "Your CafeQR subscription is not active, so this data is unavailable.");
            case 403 -> new BackendException(403, detail != null ? detail : "Your account does not have access to this data.");
            case 404 -> new BackendException(404, detail != null ? detail : "Not found.");
            default -> status >= 500
                    ? new BackendException(status, "CafeQR had a problem answering this. Please try again shortly.")
                    : new BackendException(status, detail != null ? detail : "The request could not be completed.");
        };
    }

    private static String message(JsonNode root, String fallback) {
        String m = root.path("message").asString(null);
        return m == null || m.isBlank() ? fallback : m;
    }
}
