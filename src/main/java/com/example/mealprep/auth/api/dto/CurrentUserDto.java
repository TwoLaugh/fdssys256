package com.example.mealprep.auth.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response shape of {@code GET /api/v1/auth/me} — the {@link UserDto} projection plus {@code
 * isAdmin} (frontend-gaps P3, admin page spec §5): whether the user is on the project-wide admin
 * allowlist ({@code mealprep.admin.user-ids}). Lets the shell decide admin-nav visibility from the
 * session probe it already makes, instead of an extra {@code /admin/status} round trip (and the
 * hidden-nav flash it caused).
 *
 * <p>Deliberately a separate record from {@link UserDto}: {@code UserDto} is consumed cross-module
 * (household member joins, register/password responses) where admin-ness is irrelevant — only the
 * session probe carries it.
 */
public record CurrentUserDto(UUID userId, String username, Instant createdAt, boolean isAdmin) {}
