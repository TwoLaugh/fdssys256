package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Manual user override of the entire taste profile document — bound to the PUT endpoint. The
 * supplied document replaces the persisted one verbatim; {@code expectedVersion} must equal the
 * current entity {@code @Version} (mismatch → 409). Writes one audit row with {@code change_type =
 * MANUAL_OVERRIDE} and one version snapshot with {@code trigger = MANUAL}.
 */
public record UpdateTasteProfileRequest(
    @NotNull @Valid TasteProfileDocument document, @Min(0) long expectedVersion) {}
