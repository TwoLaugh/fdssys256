package com.example.mealprep.provisions.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ExpiryInferenceService}. v1 (01h) ships with an empty rule registry; the
 * tests also verify first-non-empty-wins iteration over a non-empty list via stub rules. Lives in
 * the service's package so it can construct the package-private type directly.
 */
class ExpiryInferenceServiceTest {

  @Test
  void emptyRegistry_returnsEmpty() {
    ExpiryInferenceService service = new ExpiryInferenceService(List.of());
    Optional<LocalDate> result =
        service.inferExpiry("cheese:cheddar", "dairy", LocalDate.parse("2026-05-10"));
    assertThat(result).isEmpty();
  }

  @Test
  void firstNonEmpty_wins() {
    ExpiryRule empty = (k, c, d) -> Optional.empty();
    ExpiryRule first = (k, c, d) -> Optional.of(LocalDate.parse("2026-05-17"));
    ExpiryRule second = (k, c, d) -> Optional.of(LocalDate.parse("2026-05-31"));
    ExpiryInferenceService service = new ExpiryInferenceService(List.of(empty, first, second));

    Optional<LocalDate> result =
        service.inferExpiry("cheese:cheddar", "dairy", LocalDate.parse("2026-05-10"));
    assertThat(result).contains(LocalDate.parse("2026-05-17"));
  }

  @Test
  void allRulesEmpty_returnsEmpty() {
    ExpiryRule empty = (k, c, d) -> Optional.empty();
    ExpiryInferenceService service = new ExpiryInferenceService(List.of(empty, empty));
    Optional<LocalDate> result =
        service.inferExpiry("cheese:cheddar", "dairy", LocalDate.parse("2026-05-10"));
    assertThat(result).isEmpty();
  }

  @Test
  void nullRulesList_treatedAsEmpty() {
    ExpiryInferenceService service = new ExpiryInferenceService(null);
    Optional<LocalDate> result =
        service.inferExpiry("cheese:cheddar", "dairy", LocalDate.parse("2026-05-10"));
    assertThat(result).isEmpty();
  }
}
