package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * One cooking-method bioavailability fact ("raw spinach > steamed for folate; reverse for
 * lycopene"). Keyed by {@code subjectKey} (single ingredient) + {@code method}.
 *
 * <p><b>LLD divergence</b>: shape inferred — see {@link NutritionalPairingDto}.
 *
 * @param subjectKey ingredient mapping key
 * @param method cooking-method key (e.g. {@code "raw"}, {@code "steam"}, {@code "roast"})
 * @param effect short description of the bioavailability effect
 * @param magnitude signed relative magnitude (positive = boost, negative = reduction)
 * @param payload opaque structured details — shape per v1 table
 */
public record MethodBioavailabilityDto(
    String subjectKey, String method, String effect, BigDecimal magnitude, JsonNode payload) {}
