package com.example.mealprep.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/auth/login}. Deliberately no {@code @ValidPassword} on login — we
 * never reveal which field made the credentials invalid.
 */
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
