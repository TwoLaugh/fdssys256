package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.example.mealprep.nutrition.validation.ValidMicros;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/nutrition/intake/{date}/snacks}.
 *
 * <p>{@code deductFromPantry = true} deducts {@code quantityG} from the pantry row matching {@code
 * ingredientMappingKey} (nutrition-01l, via the provisions standalone-consumption path). The key is
 * required when the flag is set; without it the request fails with 400 rather than silently
 * skipping the deduction.
 */
public record LogSnackRequest(
    @NotBlank @Size(min = 1, max = 255) String freeText,
    @Size(max = 255) String ingredientMappingKey,
    @NotNull @Min(0) BigDecimal quantityG,
    @NotNull @Min(0) Integer calories,
    @NotNull @Min(0) BigDecimal proteinG,
    @NotNull @Min(0) BigDecimal carbsG,
    @NotNull @Min(0) BigDecimal fatG,
    @Min(0) BigDecimal fibreG,
    @ValidMicros JsonNode micros,
    @NotNull IntakeSource source,
    Boolean deductFromPantry) {}
