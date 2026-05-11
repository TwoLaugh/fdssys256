package com.example.mealprep.nutrition.domain.service.internal;

import org.springframework.stereotype.Component;

/**
 * Idempotent search-term normaliser: lowercase + trim + collapse internal whitespace to a single
 * space. All writes to {@code IngredientMapping.searchTerm} go through it; all reads by {@code
 * searchTerm} go through it.
 *
 * <p>LLD line 974 — "Same normalisation as Provisions." Lives in nutrition for now; the helper
 * graduates to a shared util when the provisions module also needs it.
 */
@Component
public class IntakeKeyNormaliser {

  /** Returns {@code raw} normalised; {@code null} is returned as {@code null}. */
  public String normalise(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    return trimmed.toLowerCase().replaceAll("\\s+", " ");
  }
}
