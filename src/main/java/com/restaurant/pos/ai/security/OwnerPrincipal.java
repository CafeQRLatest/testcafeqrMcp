package com.restaurant.pos.ai.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/**
 * The café owner/manager behind a request. {@code token} is their short-lived ai:read CafeQR token, which
 * every tool forwards to cafe-qr-backend, so the backend's own tenant and role checks always apply.
 */
public record OwnerPrincipal(
        String token,
        UUID clientId,
        UUID orgId,
        UUID userId,
        String email,
        String role) {

    public static OwnerPrincipal from(Jwt jwt) {
        return new OwnerPrincipal(
                jwt.getTokenValue(),
                uuid(jwt.getClaimAsString("clientId")),
                uuid(jwt.getClaimAsString("orgId")),
                uuid(jwt.getClaimAsString("userId")),
                jwt.getSubject(),
                jwt.getClaimAsString("role"));
    }

    public static OwnerPrincipal from(Object authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return from(jwtAuth.getToken());
        }
        throw new IllegalStateException("Request is not authenticated with a CafeQR AI token");
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "OwnerPrincipal[clientId=" + clientId + ", orgId=" + orgId + ", userId=" + userId + ", role=" + role + "]";
    }
}
