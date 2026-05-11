package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.validation.ValidDirectiveInstruction;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * JSONB document carrying the directive's action + target + scope + optional duration + extras.
 * Persisted on {@code HealthDirective.instructionPayload} and {@code userModificationJson}.
 *
 * <p>The {@link ValidDirectiveInstruction} class-level constraint runs the deterministic schema
 * gate (LLD line 869): {@code action} ∈ known set; {@code target} non-blank for {@code
 * restrict_ingredient} / {@code adjust_target}; {@code duration.type == "staged_protocol"} requires
 * ordered, non-overlapping {@code phases} whose weeks sum > 0.
 */
@ValidDirectiveInstruction
public record DirectiveInstructionDocument(
    String action,
    String target,
    String scope,
    DirectiveDurationDto duration,
    Map<String, JsonNode> extras) {}
