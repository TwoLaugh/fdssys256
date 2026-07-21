package com.example.mealprep.nutrition.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code POST /api/v1/nutrition/admin/ingredient-mappings/seed}: the G05 seed artifact
 * as-is — its {@code _meta} stamp (echoed back in the report for audit) plus the rows.
 */
public record IngredientMappingSeedRequest(
    @JsonProperty("_meta") JsonNode meta,
    @NotNull @Size(min = 1) @Valid List<IngredientMappingSeedRow> rows) {}
