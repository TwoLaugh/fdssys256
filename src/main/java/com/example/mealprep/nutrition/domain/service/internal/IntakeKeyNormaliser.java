package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import org.springframework.stereotype.Component;

/**
 * Idempotent search-term normaliser: lowercase + trim + collapse internal whitespace to a single
 * space. All writes to {@code IngredientMapping.searchTerm} go through it; all reads by {@code
 * searchTerm} go through it.
 *
 * <p>LLD line 974 — "Same normalisation as Provisions." The normalisation algorithm graduated to
 * the shared {@code core} util {@link IngredientMappingKeys} (core-03); this {@code @Component} is
 * kept because nutrition injects it widely, and its body now delegates so there is a single source
 * of truth. (The shared util pins {@link java.util.Locale#ROOT}; the prior body used the default
 * locale — equivalent on deterministic ASCII keys, and the deliberate fix for the Turkish-i edge.)
 */
@Component
public class IntakeKeyNormaliser {

  /** Returns {@code raw} normalised; {@code null} is returned as {@code null}. */
  public String normalise(String raw) {
    return IngredientMappingKeys.normalise(raw);
  }
}
