package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One prep-requirement fact — soaks, ferments, marinades, prep windows. Surfaces as {@code
 * PREP_LEAD_TIME} planner hints.
 *
 * <p><b>LLD divergence</b>: shape inferred — see {@link NutritionalPairingDto}.
 *
 * @param subjectKeys ingredient mapping keys this requirement applies to
 * @param requirement short description (e.g. "overnight soak", "ferment 48h")
 * @param leadTimeHours minimum lead time in hours
 * @param payload opaque structured details — shape per v1 table
 */
public record PrepRequirementDto(
    List<String> subjectKeys, String requirement, Integer leadTimeHours, JsonNode payload) {}
