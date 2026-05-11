package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Complexity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Per-version character fingerprint, refreshed only on branch creation (LLD line 113).
 *
 * <p>New in recipe-01d as a populated record — the OpenAPI placeholder declared earlier becomes a
 * real Jackson-serialised JSONB blob stored on {@code recipe_versions.character_fingerprint}.
 * Pipeline-driven AI inference (richer techniques/textures) ships with recipe-01k; 01d derives a
 * minimal fingerprint from the body when the caller doesn't pass {@code fingerprintOverride}.
 */
public record CharacterFingerprintDto(
    @Size(max = 5) List<@Size(max = 160) String> definingIngredients,
    @Size(max = 5) List<@Size(max = 64) String> definingTechniques,
    @Size(max = 5) List<@Size(max = 64) String> textureEssentials,
    @Size(max = 5) List<@Size(max = 64) String> flavourAnchors,
    @NotNull Complexity complexityTier,
    @Size(max = 64) String cuisineAnchor) {}
