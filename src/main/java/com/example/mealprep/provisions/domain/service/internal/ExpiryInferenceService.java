package com.example.mealprep.provisions.domain.service.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Registry of {@link ExpiryRule} implementations. v1 (01h) ships with zero registered rules —
 * Spring injects an empty list and {@link #inferExpiry} always returns {@link Optional#empty()}.
 * The rule data + {@code ItemNearingExpiryEvent} sweep lands in provisions-01k.
 *
 * <p>First-non-empty-wins iteration over the rule list preserves future ordering flexibility.
 */
@Component
class ExpiryInferenceService {

  private final List<ExpiryRule> rules;

  ExpiryInferenceService(List<ExpiryRule> rules) {
    this.rules = rules == null ? List.of() : List.copyOf(rules);
  }

  Optional<LocalDate> inferExpiry(
      String ingredientMappingKey, String category, LocalDate deliveredOn) {
    for (ExpiryRule rule : rules) {
      Optional<LocalDate> inferred = rule.infer(ingredientMappingKey, category, deliveredOn);
      if (inferred.isPresent()) {
        return inferred;
      }
    }
    return Optional.empty();
  }
}
