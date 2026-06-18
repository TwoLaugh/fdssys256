package com.example.mealprep.planner.domain.service.internal.additions;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.AdditionKind;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.service.internal.additions.AdditionPairingResult.AdditionPlacement;
import com.example.mealprep.preference.api.dto.FilterContext;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic gap-fill for in-meal additions (Phase 2 — {@code
 * design/nutrition/portion-scaling-and-additions.md}). Reads the chosen plan's projected coverage
 * (after portion scaling): the residual daily calories + the short micronutrients, then greedily
 * picks up to {@link #MAX_ADDITIONS} catalogue candidates that best close them — allergy-checked
 * against the household via the same {@link HardConstraintFilterService} the Stage-A hard filter
 * and {@code AugmentationVerifier} use. The picks are then distributed across the week ({@link
 * #distributeAcrossWeek}): SPREAD across each day's meals (round-robin by slot, never all piled on
 * one carrier) and VARIED day-to-day (a window sliding over a ranked {@link #BENCH_SIZE} bench), so
 * the eater isn't served the identical three sides on the same meal every day while each day's total
 * still rises by the picks' (USDA-derived) nutrition.
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

  /**
   * Size of the variety bench — the ranked pool of gap-relevant candidates the per-day window slides
   * over so consecutive days rotate through DIFFERENT sides instead of the identical set every day.
   * Larger than {@link #MAX_ADDITIONS} so there are alternatives to rotate in.
   */
  private static final int BENCH_SIZE = 6;

  /** Don't bother adding for a trivial calorie gap (portion scaling already got most of it). */
  private static final double MIN_RESIDUAL_KCAL = 80;

  /** Weight calories vs a single micro's gap-fill — the residual kcal is the primary driver. */
  private static final double CALORIE_WEIGHT = 2.0;

  /** A SIDE_RECIPE candidate must be side-sized (a small standalone dish, not a full main). */
  private static final int SIDE_MAX_KCAL = 350;

  /** Cap side candidates so the greedy stays tractable + ingredients aren't swamped. */
  private static final int MAX_SIDE_CANDIDATES = 6;

  private final AdditionNutritionResolver resolver;
  private final HardConstraintFilterService hardConstraintFilterService;
  private final AiService aiService;

  IngredientAdditionPlanner(
      AdditionNutritionResolver resolver,
      HardConstraintFilterService hardConstraintFilterService,
      AiService aiService) {
    this.resolver = resolver;
    this.hardConstraintFilterService = hardConstraintFilterService;
    this.aiService = aiService;
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
      return assignments; // nothing meaningfully closes the gap
    }
    int perDayCount = picked.size();
    // A deeper ranked bench — the greedy's diverse picks FIRST (so day 0 is optimal) then the
    // next-best gap-relevant alternatives — so consecutive days can rotate through DIFFERENT sides
    // instead of eating the identical set all week.
    List<Addition> bench = buildBench(picked, safe, residualKcal, shortMicros);
    log.debug(
        "Phase-2 additions: residual {}kcal + {} short micros -> {} picks/day from a bench of {} ({})",
        Math.round(residualKcal),
        shortMicros.size(),
        perDayCount,
        bench.size(),
        bench.stream().map(Addition::name).toList());

    // LLM appropriateness gate (inc 3): a natural-language note per addition. The SLOT is chosen
    // deterministically (round-robin across the day's meals) so additions always SPREAD instead of
    // piling on one carrier; the LLM note just makes each pairing read naturally.
    Map<String, AdditionPlacement> placements = pairWithLlm(bench, assignments, ctx);
    return distributeAcrossWeek(assignments, bench, perDayCount, placements);
  }

  /**
   * The variety bench: the greedy's diverse gap-filling picks FIRST (preserving an optimal day 0),
   * then the next-best still-gap-relevant candidates appended by descending score, capped at {@link
   * #BENCH_SIZE}. {@link #distributeAcrossWeek} slides a per-day window over this list so consecutive
   * days rotate through different sides. Falls back to just the greedy picks when no extra candidate
   * is gap-relevant.
   */
  private static List<Addition> buildBench(
      List<Addition> picked,
      List<Addition> safe,
      double residualKcal,
      Map<String, Double> shortMicros) {
    List<Addition> bench = new ArrayList<>(picked);
    Set<String> have = new HashSet<>();
    for (Addition a : picked) {
      have.add(a.name());
    }
    safe.stream()
        .filter(a -> !have.contains(a.name()))
        .filter(a -> score(a, residualKcal, shortMicros) > 0)
        .sorted(
            Comparator.comparingDouble((Addition a) -> score(a, residualKcal, shortMicros))
                .reversed())
        .forEach(
            a -> {
              if (bench.size() < BENCH_SIZE) {
                bench.add(a);
              }
            });
    return bench;
  }

  /**
   * Attach {@code perDayCount} additions to EACH day, (a) spread across the day's meals — pick j
   * lands on the j-th meal (round-robin by slot index) so they never all pile on one carrier — and
   * (b) varied across days — a window into {@code bench} that slides by one each day, so consecutive
   * days serve different sides while day 0 keeps the optimal diverse set. The {@code placements} map
   * (LLM, keyed by addition name) supplies the natural-language note when present.
   */
  private static List<SlotAssignment> distributeAcrossWeek(
      List<SlotAssignment> assignments,
      List<Addition> bench,
      int perDayCount,
      Map<String, AdditionPlacement> placements) {
    if (bench.isEmpty() || perDayCount <= 0) {
      return assignments;
    }
    // Recipe-bearing slots grouped by day, in date order (TreeMap) so the day index is stable.
    Map<LocalDate, List<SlotAssignment>> byDay = new TreeMap<>();
    for (SlotAssignment a : assignments) {
      if (a.onDate() != null && a.recipeId() != null) {
        byDay.computeIfAbsent(a.onDate(), k -> new ArrayList<>()).add(a);
      }
    }
    int benchSize = bench.size();
    Map<SlotAssignment, List<Addition>> toAttach = new IdentityHashMap<>();
    int dayIndex = 0;
    for (List<SlotAssignment> slots : byDay.values()) {
      if (slots.isEmpty()) {
        dayIndex++;
        continue;
      }
      slots.sort(Comparator.comparingInt(SlotAssignment::slotIndex));
      int picksToday = Math.min(perDayCount, benchSize);
      for (int j = 0; j < picksToday; j++) {
        Addition pick = bench.get((dayIndex + j) % benchSize); // sliding window → day-to-day variety
        // Round-robin across the day's meals, the starting meal rotated by day so additions land on
        // a DIFFERENT meal each day (every meal — breakfast included — gets sides over the week)
        // instead of always the same first-N meals.
        SlotAssignment target = slots.get((dayIndex + j) % slots.size());
        AdditionPlacement p = placements.get(pick.name());
        Addition noted =
            (p != null && p.note() != null && !p.note().isBlank()) ? withNote(pick, p.note()) : pick;
        toAttach.computeIfAbsent(target, k -> new ArrayList<>()).add(noted);
      }
      dayIndex++;
    }
    List<SlotAssignment> out = new ArrayList<>(assignments.size());
    for (SlotAssignment a : assignments) {
      List<Addition> adds = toAttach.get(a);
      out.add(adds == null ? a : a.withAdditions(adds));
    }
    return out;
  }

  /**
   * Ask the AI to assign each picked addition a meal slot + note. Returns name→placement, or an
   * empty map (→ deterministic fallback) when there is no AI bean or the call fails/degrades.
   */
  private Map<String, AdditionPlacement> pairWithLlm(
      List<Addition> picked, List<SlotAssignment> assignments, PlanCompositionContext ctx) {
    if (aiService == null) {
      return Map.of();
    }
    try {
      String additionsText =
          picked.stream()
              .map(a -> "- " + a.name() + " — " + (a.reasoning() == null ? "" : a.reasoning()))
              .collect(Collectors.joining("\n"));
      AdditionPairingResult result =
          aiService.execute(
              new AdditionPairingTask(
                  additionsText, mealsByKind(assignments, ctx), distinctKinds(assignments), null, null));
      if (result == null || result.placements() == null) {
        return Map.of();
      }
      Map<String, AdditionPlacement> byName = new LinkedHashMap<>();
      for (AdditionPlacement p : result.placements()) {
        if (p != null && p.additionName() != null) {
          byName.put(p.additionName(), p);
        }
      }
      return byName;
    } catch (RuntimeException ex) {
      log.warn("Addition pairing AI unavailable ({}); using deterministic placement", ex.toString());
      return Map.of();
    }
  }

  /** "BREAKFAST: Oatmeal Bowl\nLUNCH: …" for the first planned day, to give the LLM the dishes. */
  private static String mealsByKind(List<SlotAssignment> assignments, PlanCompositionContext ctx) {
    Map<UUID, String> names = recipeNames(ctx);
    LocalDate first =
        assignments.stream()
            .map(SlotAssignment::onDate)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
    Map<String, String> byKind = new LinkedHashMap<>();
    for (SlotAssignment a : assignments) {
      if (first != null
          && first.equals(a.onDate())
          && a.kind() != null
          && a.recipeId() != null) {
        byKind.putIfAbsent(a.kind().name(), names.getOrDefault(a.recipeId(), "a meal"));
      }
    }
    return byKind.entrySet().stream()
        .map(e -> e.getKey() + ": " + e.getValue())
        .collect(Collectors.joining("\n"));
  }

  private static String distinctKinds(List<SlotAssignment> assignments) {
    return assignments.stream()
        .filter(a -> a.kind() != null && a.recipeId() != null)
        .map(a -> a.kind().name())
        .distinct()
        .collect(Collectors.joining(", "));
  }

  private static Map<UUID, String> recipeNames(PlanCompositionContext ctx) {
    Map<UUID, String> names = new LinkedHashMap<>();
    if (ctx.recipePool() != null && ctx.recipePool().recipes() != null) {
      for (RecipeDto r : ctx.recipePool().recipes()) {
        if (r != null && r.id() != null) {
          names.putIfAbsent(r.id(), r.name() == null ? "a meal" : r.name());
        }
      }
    }
    return names;
  }

  private static Addition withNote(Addition a, String note) {
    return new Addition(
        a.kind(),
        a.name(),
        a.ingredientMappingKey(),
        a.recipeId(),
        a.quantity(),
        a.unit(),
        a.grams(),
        a.nutrition(),
        note);
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

  /**
   * Allergy-safe addition candidates for the household: the USDA ingredient catalogue (kind
   * INGREDIENT) plus side-dish recipes from the pool (kind SIDE_RECIPE). Both compete in the same
   * greedy gap-fill, ranked purely by how well they close the residual + short micros.
   */
  private List<Addition> allergySafeAdditions(PlanCompositionContext ctx) {
    List<UUID> eaters = eatersOf(ctx);
    List<Addition> safe = new ArrayList<>();
    for (AdditionCandidate c : AdditionCandidateCatalogue.CANDIDATES) {
      if (passesAllergy(eaters, List.of(c.ingredientKey()))) {
        safe.add(resolver.resolve(c));
      }
    }
    safe.addAll(sideCandidates(ctx, eaters));
    return safe;
  }

  /**
   * Side-dish recipe candidates: small, nutrition-bearing pool recipes the household can safely eat,
   * carrying their own per-serving nutrition. The proxy for "dishType = side" is a {@code snack}-
   * tagged recipe under {@link #SIDE_MAX_KCAL} (the pool has no first-class side classification yet —
   * a real {@code dishType} tag would replace this filter).
   */
  private List<Addition> sideCandidates(PlanCompositionContext ctx, List<UUID> eaters) {
    if (ctx.recipePool() == null || ctx.recipePool().recipes() == null) {
      return List.of();
    }
    List<Addition> sides = new ArrayList<>();
    for (RecipeDto r : ctx.recipePool().recipes()) {
      if (sides.size() >= MAX_SIDE_CANDIDATES) {
        break;
      }
      RecipeVersionDto v = r.currentVersionBody();
      if (v == null || v.metadata() == null || v.nutritionPerServing() == null) {
        continue;
      }
      List<String> meals = v.metadata().mealTypes();
      if (meals == null || !meals.contains("snack")) {
        continue; // snack-tag stands in for "side dish" until a real dishType lands
      }
      int kcal = v.nutritionPerServing().calories();
      if (kcal <= 0 || kcal > SIDE_MAX_KCAL) {
        continue; // side-sized only — not a full main
      }
      List<String> keys =
          v.ingredients() == null
              ? List.of()
              : v.ingredients().stream()
                  .map(IngredientDto::ingredientMappingKey)
                  .filter(k -> k != null && !k.isBlank())
                  .toList();
      if (!passesAllergy(eaters, keys)) {
        continue;
      }
      sides.add(
          new Addition(
              AdditionKind.SIDE_RECIPE,
              r.name() == null ? "side dish" : r.name(),
              null,
              r.id(),
              BigDecimal.ONE,
              "serving",
              null,
              v.nutritionPerServing(),
              "side dish"));
    }
    return sides;
  }

  private static List<UUID> eatersOf(PlanCompositionContext ctx) {
    return ctx.slotSkeletons() == null
        ? List.of()
        : ctx.slotSkeletons().stream()
            .flatMap(sk -> sk.eaters() == null ? java.util.stream.Stream.<UUID>of() : sk.eaters().stream())
            .distinct()
            .toList();
  }

  /** Household allergy/diet gate; empty eaters or empty keys → nothing to block on (pass). */
  private boolean passesAllergy(List<UUID> eaters, List<String> ingredientKeys) {
    if (eaters.isEmpty() || ingredientKeys.isEmpty()) {
      return true;
    }
    return hardConstraintFilterService
        .checkForHousehold(eaters, ingredientKeys, FilterContext.ANY)
        .passes();
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

}
