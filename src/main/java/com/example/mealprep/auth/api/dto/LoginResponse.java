package com.example.mealprep.auth.api.dto;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/auth/login}. The raw session token is delivered exclusively via
 * {@code Set-Cookie}; this record never carries it.
 */
public record LoginResponse(UUID userId, String username) {}
