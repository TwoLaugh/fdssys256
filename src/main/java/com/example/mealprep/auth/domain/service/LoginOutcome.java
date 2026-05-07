package com.example.mealprep.auth.domain.service;

import com.example.mealprep.auth.api.dto.UserDto;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-layer return of a successful login or auto-login-after-register.
 *
 * <p>{@code rawSessionToken} is the only place the freshly-issued token leaves the server beyond
 * the {@code Set-Cookie} header. The controller is the ONLY caller permitted to see it; it places
 * the value in a cookie and immediately discards the reference. Encoding the token into a dedicated
 * record makes the boundary auditable.
 */
public record LoginOutcome(
    UserDto user, UUID sessionId, Instant sessionExpiresAt, String rawSessionToken) {}
