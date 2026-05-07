package com.example.mealprep.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public projection of a user. Deliberately excludes operational fields ({@code passwordHash},
 * {@code failedLoginCount}, {@code lockedUntil}, {@code deletedAt}, {@code lastLoginIp}).
 */
public record UserDto(UUID userId, String username, Instant createdAt) {}
