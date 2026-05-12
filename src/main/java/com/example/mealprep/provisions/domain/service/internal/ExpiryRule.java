package com.example.mealprep.provisions.domain.service.internal;

import java.time.LocalDate;
import java.util.Optional;

/**
 * SPI for expiry-inference heuristics. v1 ships with zero registered rules (rule data deferred per
 * LLD line 737); a follow-up ticket (provisions-01k) lands the rule set. Each implementation
 * returns an inferred expiry {@link LocalDate} for the given {@code ingredientMappingKey + category
 * + deliveredOn}, or {@link Optional#empty()} if the rule does not match.
 *
 * <p>{@link ExpiryInferenceService} iterates the registered rules in Spring-injection order and
 * returns the first non-empty result.
 */
public interface ExpiryRule {

  Optional<LocalDate> infer(String ingredientMappingKey, String category, LocalDate deliveredOn);
}
