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
 * <p>Per-day macro + micro totals are summed by {@link DailyMacroAggregator} from each recipe's
 * {@code RecipeVersionDto.nutritionPerServing} (one serving per slot, per the primary eater). A
 * recipe with no computed nutrition contributes 0. The {@code micros} map carries the per-serving
 * micronutrient totals keyed by source nutrient key.
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
    Map<String, BigDecimal> micros,
    // Per-micro lowest-trust provenance for this day: measured < derived < estimated. Mirrors the
    // `micros` keys; used by RollupBuilder to surface how each coverage figure was sourced.
    Map<String, String> microSources) {

  static Builder builder(LocalDate date) {
    return new Builder(date);
  }

  /** Provenance trust rank — higher = lower trust; the "worst" source wins a blend. */
  static int trustRank(String source) {
    return "estimated".equals(source) ? 2 : ("derived".equals(source) ? 1 : 0);
  }

  /** Mutable accumulator; one instance per date bucket while walking assignments. */
  public static final class Builder {

    private final LocalDate date;
    private int kcal;
    private BigDecimal proteinG = BigDecimal.ZERO;
    private BigDecimal fatG = BigDecimal.ZERO;
    private BigDecimal carbsG = BigDecimal.ZERO;
    private BigDecimal fibreG = BigDecimal.ZERO;
    private BigDecimal saturatedFatG = BigDecimal.ZERO;
    private final Map<String, BigDecimal> micros = new LinkedHashMap<>();
    private final Map<String, String> microSources = new LinkedHashMap<>();

    Builder(LocalDate date) {
      this.date = date;
    }

    /**
     * Deep copy of this builder's running accumulators — used by the incremental Stage-A scorer so
     * each beam child folds a slot into its OWN copy of the parent's per-day totals instead of
     * sharing (and corrupting) sibling children's accumulators. The copied {@code micros} /
     * {@code microSources} maps preserve insertion order, so a later {@code build()} on the copy is
     * byte-identical to building the original after the same delta sequence.
     */
    Builder copy() {
      Builder b = new Builder(date);
      b.kcal = this.kcal;
      b.proteinG = this.proteinG;
      b.fatG = this.fatG;
      b.carbsG = this.carbsG;
      b.fibreG = this.fibreG;
      b.saturatedFatG = this.saturatedFatG;
      b.micros.putAll(this.micros);
      b.microSources.putAll(this.microSources);
      return b;
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

    /** Record a micro's provenance, keeping the lowest-trust source seen for that key this day. */
    Builder addMicroSource(String key, String source) {
      String s = source == null ? "measured" : source;
      this.microSources.merge(key, s, (a, b) -> trustRank(b) > trustRank(a) ? b : a);
      return this;
    }

    DailyMacroTotals build() {
      return new DailyMacroTotals(
          date, kcal, proteinG, fatG, carbsG, fibreG, saturatedFatG,
          Map.copyOf(micros), Map.copyOf(microSources));
    }
  }
}
