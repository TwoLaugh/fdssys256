package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * Body for {@code POST /api/v1/nutrition/health-directives/{directiveId}/accept}. {@code
 * userModification} (nullable) overrides the persisted instruction — re-validated against the same
 * schema gate so the override can't bypass the validator (LLD line 1007).
 */
public record AcceptDirectiveRequest(
    @Valid DirectiveInstructionDocument userModification, @Min(0) long expectedVersion) {}
