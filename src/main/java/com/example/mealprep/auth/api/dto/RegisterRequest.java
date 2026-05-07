package com.example.mealprep.auth.api.dto;

import com.example.mealprep.auth.validation.ValidPassword;
import com.example.mealprep.auth.validation.ValidUsername;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/auth/register}. */
public record RegisterRequest(
    @NotBlank @ValidUsername String username, @NotBlank @ValidPassword String password) {}
