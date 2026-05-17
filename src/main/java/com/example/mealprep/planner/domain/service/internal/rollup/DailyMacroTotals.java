package com.example.mealprep.planner.domain.service.internal.rollup;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable per-day macro totals carrier shared by {@link DailyMacroAggregator} consumers (01f's
 * {@code RollupBuilder} and 01e's refactored {@code NutritionFloorGate}). An internal aggregation
 * shape inside {@code domain.service.internal.rollup} — not a published API DTO. It is {@code
 * public} only so the refactored gate (in the sibling {@code scoring} package) can iterate the
 * returned map; the {@link Builder} stays package-private.
 *
 * <p><b>01f codebase divergence — recipe nutrition not exposed</b>: {@code RecipeVersionDto}
 * carries no {@code nutritionPerServing} JsonNode in this codebase (the ticket's verbatim snippet
 * assumed an idealised LLD shape). 01e established that every per-day macro total is therefore
 * {@code 0}; 01f preserves that exactly so {@code NutritionFloorGateTest} stays byte-identical. The
 * {@code DailyMacroAggregator} is still the single seam to plug real per-serving macros into when
 * recipe-01h's nutrition pipeline exposes them — see {@code DailyMacroAggregator}.
 *
 * <p>Built via the static nested mutable {@link Builder} (Lombok's {@code @Builder} does not work
 * on records; the ticket gotcha #7 calls for a hand-rolled builder).
 */
public record DailyMacroTotals(
    LocalDate date,
    int kcal,
    BigDecimal proteinG,
    BigDecimal fatG,
    BigDecimal carbsG,
    BigDecimal fibreG,
    BigDecimal saturatedFatG,
    Map<String, BigDecimal> micros) {

  static Builder builder(LocalDate date) {
    return new Builder(date);
  }

  /** Mutable accumulator; one instance per date bucket while walking assignments. */
  static final class Builder {

    private final LocalDate date;
    private int kcal;
    private BigDecimal proteinG = BigDecimal.ZERO;
    private BigDecimal fatG = BigDecimal.ZERO;
    private BigDecimal carbsG = BigDecimal.ZERO;
    private BigDecimal fibreG = BigDecimal.ZERO;
    private BigDecimal saturatedFatG = BigDecimal.ZERO;
    private final Map<String, BigDecimal> micros = new LinkedHashMap<>();

    Builder(LocalDate date) {
      this.date = date;
    }

    Builder addKcal(int delta) {
      this.kcal += delta;
      return this;
    }

    Builder addProtein(BigDecimal delta) {
      this.proteinG = this.proteinG.add(delta);
      return this;
    }

    Builder addFat(BigDecimal delta) {
      this.fatG = this.fatG.add(delta);
      return this;
    }

    Builder addCarbs(BigDecimal delta) {
      this.carbsG = this.carbsG.add(delta);
      return this;
    }

    Builder addFibre(BigDecimal delta) {
      this.fibreG = this.fibreG.add(delta);
      return this;
    }

    Builder addSaturatedFat(BigDecimal delta) {
      this.saturatedFatG = this.saturatedFatG.add(delta);
      return this;
    }

    Builder addMicro(String key, BigDecimal delta) {
      this.micros.merge(key, delta, BigDecimal::add);
      return this;
    }

    DailyMacroTotals build() {
      return new DailyMacroTotals(
          date, kcal, proteinG, fatG, carbsG, fibreG, saturatedFatG, Map.copyOf(micros));
    }
  }
}
