package com.example.mealprep.auth.api.dto;

import com.example.mealprep.auth.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code PUT /api/v1/auth/password}. Deliberately no {@code @ValidPassword} on {@code
 * currentPassword} — its validity is determined by the BCrypt verify, not by shape rules, and a
 * "weak" current password must NOT block the rotation that's trying to replace it.
 */
public record PasswordChangeRequest(
    @NotBlank String currentPassword, @NotBlank @ValidPassword String newPassword) {}
