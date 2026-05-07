package com.example.mealprep.auth.domain.service;

import java.util.UUID;

/**
 * The principal {@code SessionAuthenticationFilter} attaches to the Spring Security {@code
 * Authentication}. {@link CurrentUserResolverImpl} unwraps it; nothing else is meant to.
 */
public record AuthenticatedPrincipal(UUID userId, UUID sessionId) {}
