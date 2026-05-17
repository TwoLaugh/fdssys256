package com.example.mealprep.planner.domain.service.internal.beamsearch;

import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.internal.scoring.ScoringEngine;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stage-A beam search. Iterates slots in week-order (date, then slotIndex), expanding each
 * beam-entry against the per-slot pool, scoring the expansion via the injected {@link
 * ScoringEngine}, and pruning to {@code config.width()}. Returns the top {@code config.topN()} as
 * {@link CandidatePlan} instances wrapped in a {@link BeamSearchOutcome}.
 *
 * <p>Timeout fallbacks per LLD §Failure Modes line 1344:
 *
 * <ul>
 *   <li>First-level — retry once with {@code width / 2}.
 *   <li>Second-level — degrade to greedy ({@code width = 1}); {@link BeamSearchOutcome
 *       #degradedToGreedy()} flag is set so the composer (01j) can flag the plan with {@code
 *       qualityWarning = true}.
 * </ul>
 *
 * <p>Pinned slots (re-opt path, planner-01i) skip expansion and scoring — every beam entry simply
 * appends the pinned {@link SlotAssignment} verbatim. For 01d's fresh-generation tests, {@code
 * pinnedAssignments} is empty.
 */
@Component
class BeamSearchEngineImpl implements BeamSearchEngine {

  private static final Logger log = LoggerFactory.getLogger(BeamSearchEngineImpl.class);

  private final HardFilterRunner hardFilterRunner;
  private final BeamPruner beamPruner;
  private final ScoringEngine scoringEngine;
  private final PlannerProperties properties;

  BeamSearchEngineImpl(
      HardFilterRunner hardFilterRunner,
      BeamPruner beamPruner,
      ScoringEngine scoringEngine,
      PlannerProperties properties) {
    this.hardFilterRunner = hardFilterRunner;
    this.beamPruner = beamPruner;
    this.scoringEngine = scoringEngine;
    this.properties = properties;
  }

  @Override
  public BeamSearchOutcome search(PlanCompositionContext ctx, BeamSearchConfig config) {
    Map<UUID, List<RecipeDto>> pool = hardFilterRunner.filterPool(ctx);
    List<MealSlotSkeleton> orderedSlots = orderSlots(ctx);

    // First attempt at the configured width.
    SearchAttempt first = attempt(ctx, config, pool, orderedSlots);
    if (!first.timedOut()) {
      return new BeamSearchOutcome(finalise(first.beam(), ctx, config), false);
    }

    // First-level fallback: retry with width / 2 (minimum 1).
    int halfWidth = Math.max(1, config.width() / 2);
    BeamSearchConfig halfConfig =
        new BeamSearchConfig(
            halfWidth, Math.min(config.topN(), halfWidth), config.maxPoolPerSlot());
    log.warn(
        "Stage-A beam search timed out at width={}; retrying with width={}",
        config.width(),
        halfWidth);
    SearchAttempt second = attempt(ctx, halfConfig, pool, orderedSlots);
    if (!second.timedOut()) {
      return new BeamSearchOutcome(finalise(second.beam(), ctx, halfConfig), false);
    }

    // Second-level fallback: greedy (width 1). qualityWarning flag set by composer (01j).
    BeamSearchConfig greedyConfig = new BeamSearchConfig(1, 1, config.maxPoolPerSlot());
    log.warn(
        "Stage-A beam search timed out twice; degrading to greedy (width=1). traceId={}",
        ctx.traceId());
    SearchAttempt greedy = attempt(ctx, greedyConfig, pool, orderedSlots);
    return new BeamSearchOutcome(finalise(greedy.beam(), ctx, greedyConfig), true);
  }

  private SearchAttempt attempt(
      PlanCompositionContext ctx,
      BeamSearchConfig config,
      Map<UUID, List<RecipeDto>> pool,
      List<MealSlotSkeleton> orderedSlots) {
    long startNanos = System.nanoTime();
    long timeoutNanos = properties.stageATimeout().toNanos();
    List<PartialPlan> beam = List.of(PartialPlan.empty(ctx.weekStartDate()));
    for (MealSlotSkeleton skel : orderedSlots) {
      long elapsedNanos = System.nanoTime() - startNanos;
      if (elapsedNanos > timeoutNanos) {
        return new SearchAttempt(beam, true);
      }
      beam = expandAndPrune(beam, skel, pool, ctx, config);
    }
    return new SearchAttempt(beam, false);
  }

  private List<MealSlotSkeleton> orderSlots(PlanCompositionContext ctx) {
    return ctx.slotSkeletons().stream()
        .sorted(
            Comparator.comparing(MealSlotSkeleton::onDate)
                .thenComparingInt(MealSlotSkeleton::slotIndex))
        .toList();
  }

  private List<PartialPlan> expandAndPrune(
      List<PartialPlan> beam,
      MealSlotSkeleton skel,
      Map<UUID, List<RecipeDto>> pool,
      PlanCompositionContext ctx,
      BeamSearchConfig config) {

    Optional<SlotAssignment> pinned =
        ctx.pinnedAssignments() == null
            ? Optional.empty()
            : ctx.pinnedAssignments().stream()
                .filter(a -> a.slotId().equals(skel.slotId()))
                .findFirst();
    if (pinned.isPresent()) {
      SlotAssignment a = pinned.get();
      return beam.stream().map(p -> p.append(a)).toList();
    }

    List<RecipeDto> slotPool = pool.getOrDefault(skel.slotId(), List.of());
    if (slotPool.isEmpty()) {
      return beam; // unfilled; composer surfaces as qualityWarning in 01j
    }

    int defaultServings = defaultServings(skel);
    List<PartialPlan> expanded = new ArrayList<>(beam.size() * slotPool.size());
    for (PartialPlan p : beam) {
      for (RecipeDto r : slotPool) {
        SlotAssignment assignment = toAssignment(skel, r, defaultServings);
        PartialPlan candidate = p.append(assignment);
        ScoreResult sr = scoringEngine.score(candidate.toCandidatePlanView(UUID.randomUUID()), ctx);
        expanded.add(candidate.withScore(sr.composite()));
      }
    }
    return beamPruner.retainTop(expanded, config.width());
  }

  private int defaultServings(MealSlotSkeleton skel) {
    if (skel.eaters() == null || skel.eaters().isEmpty()) {
      return 1;
    }
    return skel.eaters().size();
  }

  private SlotAssignment toAssignment(MealSlotSkeleton skel, RecipeDto recipe, int servings) {
    UUID versionId = recipe.currentVersionBody() == null ? null : recipe.currentVersionBody().id();
    return new SlotAssignment(
        skel.dayId(),
        skel.slotId(),
        skel.slotIndex(),
        skel.onDate(),
        skel.kind(),
        recipe.id(),
        versionId,
        recipe.currentBranchId(),
        servings,
        false);
  }

  private List<CandidatePlan> finalise(
      List<PartialPlan> beam, PlanCompositionContext ctx, BeamSearchConfig config) {
    return beam.stream()
        .limit(config.topN())
        .map(
            p ->
                p.toCandidatePlanView(
                    UUID.randomUUID(),
                    scoringEngine.score(p.toCandidatePlanView(UUID.randomUUID()), ctx)))
        .toList();
  }

  /** Inner carrier for a single attempt's outcome — beam state plus the timed-out flag. */
  private record SearchAttempt(List<PartialPlan> beam, boolean timedOut) {}
}
