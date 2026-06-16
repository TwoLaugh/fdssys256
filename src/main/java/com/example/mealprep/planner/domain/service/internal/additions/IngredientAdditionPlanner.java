package com.example.mealprep.planner.domain.service.internal.additions;

import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.preference.api.dto.FilterContext;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic gap-fill for in-meal additions (Phase 2 — {@code
 * design/nutrition/portion-scaling-and-additions.md}). Reads the chosen plan's projected coverage
 * (after portion scaling): the residual daily calories + the short micronutrients, then greedily
 * picks up to {@link #MAX_ADDITIONS} catalogue candidates that best close them — allergy-checked
 * against the household via the same {@link HardConstraintFilterService} the Stage-A hard filter
 * and {@code AugmentationVerifier} use. The picks are attached to one slot per day so every day's
 * total rises by their (USDA-derived) nutrition.
 *
 * <p>Deterministic, no AI: cheap, no tokens, no latency. The LLM appropriateness gate (inc 3) only
 * refines <i>which</i> sensible candidate pairs with <i>which</i> dish + writes the note; the math
 * of what closes the gap lives here.
 */
@Component
public class IngredientAdditionPlanner {

  private static final Logger log = LoggerFactory.getLogger(IngredientAdditionPlanner.class);

  /** At most this many additions per meal (design §Safety/bounds). */
  private static final int MAX_ADDITIONS = 3;

  /** Don't bother adding for a trivial calorie gap (portion scaling already got most of it). */
  private static final double MIN_RESIDUAL_KCAL = 80;

  /** Weight calories vs a single micro's gap-fill — the residual kcal is the primary driver. */
  private static final double CALORIE_WEIGHT = 2.0;

  private final AdditionNutritionResolver resolver;
  private final HardConstraintFilterService hardConstraintFilterService;

  IngredientAdditionPlanner(
      AdditionNutritionResolver resolver,
      HardConstraintFilterService hardConstraintFilterService) {
    this.resolver = resolver;
    this.hardConstraintFilterService = hardConstraintFilterService;
  }

  /**
   * Return {@code assignments} with in-meal additions attached (one slot per day). A no-op — returns
   * the input list unchanged — when there are no targets, no meaningful gap, or no allergy-safe
   * candidate helps.
   */
  public List<SlotAssignment> attach(
      List<SlotAssignment> assignments, RollupSummaryDocument rollup, PlanCompositionContext ctx) {
    if (assignments == null || assignments.isEmpty() || rollup == null) {
      return assignments;
    }
    NutritionCoverageDocument coverage = rollup.nutritionCoverage();
    if (coverage == null) {
      return assignments; // no targets configured → nothing to close
    }

    double residualKcal = residualCalories(coverage);
    Map<String, Double> shortMicros = shortMicros(coverage);
    if (residualKcal < MIN_RESIDUAL_KCAL && shortMicros.isEmpty()) {
      return assignments; // portion scaling already met the targets
    }

    List<Addition> safe = allergySafeAdditions(ctx);
    if (safe.isEmpty()) {
      return assignments;
    }

    List<Addition> picked = greedyPick(safe, residualKcal, shortMicros);
    if (picked.isEmpty()) {
      return assignments;
    }
    log.debug(
        "Phase-2 additions: residual {}kcal + {} short micros -> picked {}",
        Math.round(residualKcal),
        shortMicros.size(),
        picked.stream().map(Addition::name).toList());

    return attachPerDay(assignments, picked);
  }

  /** Residual daily calories from the coverage's SHORT calorie macro (0 if met / absent). */
  private static double residualCalories(NutritionCoverageDocument coverage) {
    if (coverage.macros() == null) {
      return 0;
    }
    for (NutritionTargetCoverageDocument m : coverage.macros()) {
      if (m != null
          && "calories".equalsIgnoreCase(m.key())
          && "SHORT".equals(m.status())
          && m.target() != null
          && m.projectedDailyAvg() != null) {
        return m.target().subtract(m.projectedDailyAvg()).doubleValue();
      }
    }
    return 0;
  }

  /** Short micronutrients keyed by canonical micro key → the gap (target − projected), &gt; 0. */
  private static Map<String, Double> shortMicros(NutritionCoverageDocument coverage) {
    Map<String, Double> out = new LinkedHashMap<>();
    if (coverage.micros() == null) {
      return out;
    }
    for (NutritionTargetCoverageDocument m : coverage.micros()) {
      if (m != null
          && "SHORT".equals(m.status())
          && m.key() != null
          && m.target() != null
          && m.projectedDailyAvg() != null) {
        double gap = m.target().subtract(m.projectedDailyAvg()).doubleValue();
        if (gap > 0) {
          out.put(m.key(), gap);
        }
      }
    }
    return out;
  }

  /** Resolve every catalogue candidate whose ingredient is safe for the whole household. */
  private List<Addition> allergySafeAdditions(PlanCompositionContext ctx) {
    List<java.util.UUID> eaters =
        ctx.slotSkeletons() == null
            ? List.of()
            : ctx.slotSkeletons().stream()
                .flatMap(sk -> sk.eaters() == null ? List.<java.util.UUID>of().stream() : sk.eaters().stream())
                .distinct()
                .toList();
    List<Addition> safe = new ArrayList<>();
    for (AdditionCandidate c : AdditionCandidateCatalogue.CANDIDATES) {
      if (eaters.isEmpty()
          || hardConstraintFilterService
              .checkForHousehold(eaters, List.of(c.ingredientKey()), FilterContext.ANY)
              .passes()) {
        safe.add(resolver.resolve(c));
      }
    }
    return safe;
  }

  /** Greedy knapsack-ish pick: repeatedly take the addition that best fills the remaining gap. */
  private static List<Addition> greedyPick(
      List<Addition> pool, double residualKcal, Map<String, Double> shortMicros) {
    List<Addition> remaining = new ArrayList<>(pool);
    List<Addition> picked = new ArrayList<>();
    double resKcal = residualKcal;
    Map<String, Double> gaps = new LinkedHashMap<>(shortMicros);

    while (picked.size() < MAX_ADDITIONS
        && !remaining.isEmpty()
        && (resKcal > MIN_RESIDUAL_KCAL || !gaps.isEmpty())) {
      Addition best = null;
      double bestScore = 0;
      for (Addition a : remaining) {
        double s = score(a, resKcal, gaps);
        if (s > bestScore) {
          bestScore = s;
          best = a;
        }
      }
      if (best == null) {
        break; // nothing helps the remaining gap
      }
      picked.add(best);
      remaining.remove(best);
      resKcal -= best.nutrition().calories();
      if (best.nutrition().micros() != null) {
        for (Map.Entry<String, BigDecimal> e : best.nutrition().micros().entrySet()) {
          Double gap = gaps.get(e.getKey());
          if (gap != null && e.getValue() != null) {
            double left = gap - e.getValue().doubleValue();
            if (left <= 0) {
              gaps.remove(e.getKey());
            } else {
              gaps.put(e.getKey(), left);
            }
          }
        }
      }
    }
    return picked;
  }

  /** Fraction of the residual kcal + each short micro this addition fills (capped per dimension). */
  private static double score(Addition a, double residualKcal, Map<String, Double> gaps) {
    double s = 0;
    int kcal = a.nutrition().calories();
    if (residualKcal > 0 && kcal > 0) {
      s += Math.min(kcal, residualKcal) / residualKcal * CALORIE_WEIGHT;
    }
    if (a.nutrition().micros() != null) {
      for (Map.Entry<String, BigDecimal> e : a.nutrition().micros().entrySet()) {
        Double gap = gaps.get(e.getKey());
        if (gap != null && gap > 0 && e.getValue() != null) {
          s += Math.min(e.getValue().doubleValue(), gap) / gap;
        }
      }
    }
    return s;
  }

  /**
   * Attach the picked additions to one slot per day — the highest-index recipe-bearing slot (the
   * day's largest meal). The day total is what matters (the aggregator sums per day), so a single
   * carrier slot per day is sufficient; the LLM gate (inc 3) re-homes them to the best dish.
   */
  private static List<SlotAssignment> attachPerDay(
      List<SlotAssignment> assignments, List<Addition> picked) {
    Map<LocalDate, SlotAssignment> carrierByDay = new LinkedHashMap<>();
    for (SlotAssignment a : assignments) {
      if (a.onDate() == null || a.recipeId() == null) {
        continue;
      }
      SlotAssignment current = carrierByDay.get(a.onDate());
      if (current == null || a.slotIndex() > current.slotIndex()) {
        carrierByDay.put(a.onDate(), a);
      }
    }
    List<SlotAssignment> out = new ArrayList<>(assignments.size());
    for (SlotAssignment a : assignments) {
      SlotAssignment carrier = carrierByDay.get(a.onDate());
      out.add(carrier == a ? a.withAdditions(picked) : a);
    }
    return out;
  }
}
