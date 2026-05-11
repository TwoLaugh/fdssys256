package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One side of a substitution (original or substitute). Verbatim from LLD lines 413-419 ({@code
 * SubstitutionItemRequest}).
 */
public record SubstitutionItemRequest(
    @NotBlank @Size(max = 160) String ingredientMappingKey,
    @NotNull @DecimalMin("0") BigDecimal quantity,
    @NotBlank @Size(max = 16) String unit) {}
