package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One absorption-conflict fact ("calcium blocks non-haem iron; don't pair this stir-fry with
 * milk").
 *
 * <p><b>LLD divergence</b>: shape inferred — see {@link NutritionalPairingDto}.
 *
 * @param subjectKeys ingredient mapping keys involved in the conflict
 * @param conflict short description of the conflict
 * @param severity textual severity label (e.g. {@code "warn"}, {@code "block"}) — typed enum
 *     deferred to v2 if needed
 * @param payload opaque structured details — shape per v1 table
 */
public record AbsorptionConflictDto(
    List<String> subjectKeys, String conflict, String severity, JsonNode payload) {}
