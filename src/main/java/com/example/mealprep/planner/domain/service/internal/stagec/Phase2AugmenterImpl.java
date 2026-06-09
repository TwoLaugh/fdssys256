package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.planner.api.dto.AugmentationResult;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RefineDirectiveProposal;
import com.example.mealprep.planner.config.PlannerProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link Phase2Augmenter}. Per lld/planner.md §{@code Phase2Augmenter} (lines 868-919) and
 * ticket planner-01h §"Verbatim shape snippets".
 *
 * <p><b>{@code @Transactional} is intentionally absent</b> — Phase 2 is an AI call and must run
 * outside any transaction (same rule as planner-01g Stage-C invoker). The composer (planner-01j)
 * owns the surrounding transaction boundary and calls this method outside it.
 *
 * <p><b>Degradation (skip-and-flag, per style-guide §AI Service):</b>
 *
 * <ul>
 *   <li>{@link AiUnavailableException} — cost cap / key missing / retries exhausted on a transient
 *       failure (the merged AI module surfaces exhausted transient failures as this single type;
 *       there is no separate {@code TransientAiFailureException}). Logged INFO, empty result; the
 *       composer flags the plan {@code aiAugmented = false}.
 *   <li>{@link AiInvalidResponseException} — malformed / wrong-typed LLM payload. Logged WARN,
 *       empty result — a bad payload must not brick plan composition.
 * </ul>
 */
@Component
class Phase2AugmenterImpl implements Phase2Augmenter {

  private static final Logger log = LoggerFactory.getLogger(Phase2AugmenterImpl.class);

  private static final AugmentationResult EMPTY =
      new AugmentationResult(List.of(), List.of(), List.of());

  private final AiService aiService;
  private final AugmentationVerifier verifier;
  private final AugmentationParser parser;
  private final PlannerProperties properties;

  Phase2AugmenterImpl(
      AiService aiService,
      AugmentationVerifier verifier,
      AugmentationParser parser,
      PlannerProperties properties) {
    this.aiService = aiService;
    this.verifier = verifier;
    this.parser = parser;
    this.properties = properties;
  }

  @Override
  public AugmentationResult augment(
      CandidatePlan chosenPlan,
      CandidatePlanRollupDto chosenRollup,
      PlanCompositionContext ctx,
      UUID traceId) {

    String summary = buildConstraintsSummary(ctx);
    UUID primaryUserId = resolvePrimaryUserId(ctx);
    List<Map<String, Object>> gaps = computeNutritionGaps(chosenRollup, ctx, primaryUserId);

    Phase2AugmentationTask task =
        new Phase2AugmentationTask(
            chosenRollup,
            summary,
            gaps,
            properties.maxAugmentations(),
            properties.maxRefineDirectives(),
            primaryUserId,
            traceId);

    Phase2AugmentationResponse response;
    try {
      response = aiService.execute(task);
    } catch (AiUnavailableException e) {
      log.info("Phase 2: AI unavailable; emitting empty augmentation result (skip-and-flag)");
      return EMPTY;
    } catch (AiInvalidResponseException e) {
      log.warn("Phase 2: AI returned an unusable payload; emitting empty augmentation result", e);
      return EMPTY;
    }

    if (response == null) {
      log.warn("Phase 2: null AI response; emitting empty augmentation result");
      return EMPTY;
    }

    List<Augmentation> proposed =
        response.augmentations().stream()
            .limit(properties.maxAugmentations())
            .map(parser::parse)
            .filter(Objects::nonNull)
            .toList();

    List<Augmentation> applied = new ArrayList<>();
    List<Augmentation> discarded = new ArrayList<>();
    for (Augmentation a : proposed) {
      if (verifier.passes(a, ctx)) {
        applied.add(a);
      } else {
        log.warn("Phase 2: augmentation {} discarded by verifier", a);
        discarded.add(a);
      }
    }

    List<RefineDirectiveProposal> directives =
        parseRefineDirectives(response, properties.maxRefineDirectives());

    return new AugmentationResult(
        List.copyOf(applied), List.copyOf(discarded), List.copyOf(directives));
  }

  /**
   * Select the refine-directive proposals to forward to Stage D. The raw {@link
   * RefineDirectiveProposal}s from the Phase-2 AI response are passed through verbatim (bounded by
   * {@code max} = {@code maxRefineDirectives}), null-safe via {@link
   * Phase2AugmentationResponse#refineDirectives()} so an absent/empty {@code refineDirectives}
   * array yields an empty list. The composer (planner-01j) maps each proposal onto the adaptation
   * module's {@code PlanTimeRefineDirectiveRequest} (kind mapping + per-kind {@code targetDelta})
   * and dispatches {@code AdaptationService.runPlanTimeRefineJob(...)} — see {@code
   * RefineDirectiveMapper} and {@code PlanComposer}'s Stage-D loop. Carrying the raw proposal keeps
   * every directive field (kind, target slot, from/to ingredient keys, current/target time minutes,
   * reasoning) intact for the cross-module assembly with no lossy round-trip.
   */
  private List<RefineDirectiveProposal> parseRefineDirectives(
      Phase2AugmentationResponse response, int max) {
    return response.refineDirectives().stream().limit(max).filter(Objects::nonNull).toList();
  }

  /** Short human-readable constraint context for the prompt. No PII — counts only. */
  private String buildConstraintsSummary(PlanCompositionContext ctx) {
    int households =
        ctx.hardConstraintsByUserId() == null ? 0 : ctx.hardConstraintsByUserId().size();
    int slots = ctx.slotSkeletons() == null ? 0 : ctx.slotSkeletons().size();
    int poolSize =
        ctx.recipePool() == null || ctx.recipePool().recipes() == null
            ? 0
            : ctx.recipePool().recipes().size();
    return "household members with hard constraints: "
        + households
        + "; slots: "
        + slots
        + "; recipe pool size: "
        + poolSize
        + "; week starting "
        + ctx.weekStartDate();
  }

  /**
   * Primary user = the first eater of the first slot skeleton (the context carries no explicit
   * primary; the planner treats the lead eater as the gap-detection subject per ticket §10).
   * Returns {@code null} when the context has no eaters — the gap list is then empty and the task
   * still dispatches (cost tracking just has no owning user).
   */
  private UUID resolvePrimaryUserId(PlanCompositionContext ctx) {
    if (ctx.slotSkeletons() == null) {
      return null;
    }
    return ctx.slotSkeletons().stream()
        .map(MealSlotSkeleton::eaters)
        .filter(e -> e != null && !e.isEmpty())
        .map(e -> e.get(0))
        .findFirst()
        .orElse(null);
  }

  /**
   * Minimal per-day gap detection: walk the rollup's macros against the primary user's {@code
   * TargetsDto} and flag any macro below a {@code LOWER_FLOOR} target or above an {@code
   * UPPER_LIMIT} target ({@code BOTH_BOUNDED} flags either side). Emits {@code {date, macro,
   * target, actual, direction}} entries the LLM uses to know what to fix.
   */
  private List<Map<String, Object>> computeNutritionGaps(
      CandidatePlanRollupDto rollup, PlanCompositionContext ctx, UUID primaryUserId) {
    List<Map<String, Object>> gaps = new ArrayList<>();
    if (rollup == null || rollup.perDay() == null || primaryUserId == null) {
      return gaps;
    }
    TargetsDto targets =
        ctx.nutritionByUserId() == null ? null : ctx.nutritionByUserId().get(primaryUserId);
    if (targets == null) {
      return gaps;
    }
    for (CandidateDailyRollupDto day : rollup.perDay()) {
      addGapIfBreached(gaps, day.date(), "protein", targets.protein(), day.proteinG());
      addGapIfBreached(gaps, day.date(), "carbs", targets.carbs(), day.carbsG());
      addGapIfBreached(gaps, day.date(), "fat", targets.fat(), day.fatG());
      addGapIfBreached(gaps, day.date(), "fibre", targets.fibre(), day.fibreG());
    }
    return gaps;
  }

  private void addGapIfBreached(
      List<Map<String, Object>> gaps,
      Object date,
      String macro,
      MacroTargetDto target,
      BigDecimal actual) {
    if (target == null || target.targetG() == null || actual == null) {
      return;
    }
    EnforcementDirection direction = target.direction();
    int cmp = actual.compareTo(target.targetG());
    boolean breached =
        switch (direction) {
          case LOWER_FLOOR -> cmp < 0;
          case UPPER_LIMIT -> cmp > 0;
          case BOTH_BOUNDED -> cmp != 0;
        };
    if (!breached) {
      return;
    }
    Map<String, Object> gap = new LinkedHashMap<>();
    gap.put("date", String.valueOf(date));
    gap.put("macro", macro);
    gap.put("target", target.targetG());
    gap.put("actual", actual);
    gap.put("direction", direction.name());
    gaps.add(gap);
  }
}
