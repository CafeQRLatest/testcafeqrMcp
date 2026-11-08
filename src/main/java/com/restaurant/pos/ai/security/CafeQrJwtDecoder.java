package com.restaurant.pos.ai.security;

import com.restaurant.pos.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Verifies CafeQR tokens with the backend's RSA public key (RS256) and only accepts tokens with
 * scope = ai:read, an allowed role (owner/manager) and a clientId. A normal dashboard login token is
 * rejected, so this service only ever holds read-only credentials.
 */
public class CafeQrJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(CafeQrJwtDecoder.class);
    static final String AI_READ_SCOPE = "ai:read";

    private final AiProperties properties;
    private final RestClient restClient;
    private volatile JwtDecoder delegate;

    public CafeQrJwtDecoder(AiProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.clone().baseUrl(properties.backend().baseUrl()).build();
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        JwtDecoder current = delegate();
        try {
            return current.decode(token);
        } catch (BadJwtException e) {
            // The backend may have restarted with a new key pair (no JWT_PRIVATE_KEY set): reload the
            // key from the backend once and retry. At most once per KEY_REFRESH_INTERVAL_MS.
            if (!isSignatureFailure(e) || !refreshKey(current)) {
                throw e;
            }
            return delegate().decode(token);
        }
    }

    private static final long KEY_REFRESH_INTERVAL_MS = 30_000;
    private volatile long lastRefresh;

    private static boolean isSignatureFailure(BadJwtException e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("signature");
    }

    private synchronized boolean refreshKey(JwtDecoder failed) {
        String configured = properties.security().jwtPublicKey();
        if (configured != null && !configured.isBlank()) {
            return false; // fixed key from config; nothing to reload
        }
        if (delegate != failed) {
            return true; // another request already reloaded it
        }
        long now = System.currentTimeMillis();
        if (now - lastRefresh < KEY_REFRESH_INTERVAL_MS) {
            return false;
        }
        lastRefresh = now;
        log.info("Token signature did not match; reloading public key from cafe-qr-backend");
        delegate = build(loadPublicKey());
        return true;
    }

    private JwtDecoder delegate() {
        JwtDecoder current = delegate;
        if (current == null) {
            synchronized (this) {
                if (delegate == null) {
                    delegate = build(loadPublicKey());
                }
                current = delegate;
            }
        }
        return current;
    }

    private JwtDecoder build(RSAPublicKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
        OAuth2TokenValidator<Jwt> rules = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), this::validateClaims);
        decoder.setJwtValidator(rules);
        return decoder;
    }

    OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        if (!AI_READ_SCOPE.equals(jwt.getClaimAsString("scope"))) {
            return fail("Token is not a CafeQR AI (ai:read) token");
        }
        if (jwt.getClaimAsString("clientId") == null) {
            return fail("Token has no clientId");
        }
        List<String> allowed = properties.security().allowedRoles();
        String role = jwt.getClaimAsString("role");
        if (role == null || allowed.stream().noneMatch(r -> r.equalsIgnoreCase(role))) {
            return fail("Role is not allowed to use the CafeQR assistant");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String message) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", message, null));
    }

    @SuppressWarnings("unchecked")
    private RSAPublicKey loadPublicKey() {
        String base64 = properties.security().jwtPublicKey();
        if (base64 == null || base64.isBlank()) {
            log.info("CAFEQR_JWT_PUBLIC_KEY not set; fetching public key from cafe-qr-backend");
            try {
                Map<String, Object> body = restClient.get().uri("/api/v1/public/ai/jwt-public-key")
                        .retrieve().body(Map.class);
                Map<String, Object> data = body == null ? null : (Map<String, Object>) body.get("data");
                base64 = data == null ? null : (String) data.get("publicKey");
            } catch (Exception e) {
                throw new BackendUnavailableJwtException("Cannot reach cafe-qr-backend to load its public key", e);
            }
            if (base64 == null) {
                throw new BackendUnavailableJwtException("cafe-qr-backend returned no public key", null);
            }
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid CafeQR JWT public key", e);
        }
    }

    /** Key could not be loaded; the next request retries. */
    static class BackendUnavailableJwtException extends JwtException {
        BackendUnavailableJwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
