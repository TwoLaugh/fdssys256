package com.example.mealprep.nutrition.domain.service.internal;

import java.math.BigDecimal;

/**
 * Input to {@link IngredientMappingPipeline#resolve(IngredientLookupInput)}. {@code rawTerm} is the
 * caller's verbatim user-provided string (pre-normalisation); {@code gramsEstimate} is nullable for
 * the public {@code /lookup?term=} endpoint and supplied by the (future) snack-log path.
 */
public record IngredientLookupInput(String rawTerm, BigDecimal gramsEstimate) {}
