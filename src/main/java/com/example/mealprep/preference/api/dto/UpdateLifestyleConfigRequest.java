package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Full-replacement update for a user's lifestyle config. {@code expectedVersion} is matched against
 * the row's current {@code @Version}; mismatch → 409. The document field carries the whole nested
 * structure validated via {@code @Valid} (per-section recursion) plus the class-level
 * {@code @ValidNoveltyTolerance} on the {@code noveltyTolerance} section.
 */
public record UpdateLifestyleConfigRequest(
    @NotNull @Valid LifestyleConfigDocument document, @Min(0) long expectedVersion) {}
