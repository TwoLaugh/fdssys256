package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchConfig;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Top-level plan-composition orchestrator (planner-01j). Drives Stage A (beam search) &rarr; Stage
 * B (rollup) &rarr; Stage C (LLM pick) &rarr; Phase 2 (augment) &rarr; Stage D (refine-directive
 * routing to the adaptation pipeline) &rarr; persist, per LLD §Composer and §Flow 1.
 *
 * <h2>Transaction semantics (ticket invariant #7, gotchas)</h2>
 *
 * {@link #compose} is {@code @Transactional(REQUIRED)} — the whole composition + persist runs in
 * one transaction; a failure after Stage A rolls the partial plan back. The {@link
 * AdaptationService#runPlanTimeRefineJob} calls inside are themselves
 * {@code @Transactional(REQUIRES_NEW)} (declared by adaptation-pipeline-01b) so their
 * pending-change / trace rows commit independently — the planner's rollback does NOT undo them.
 * This is the intended audit semantic: adaptation work survives composer failures.
 *
 * <p>{@link PlanGeneratedEvent} is published <b>inside</b> the transactional body so an {@code
 * AFTER_COMMIT} {@code @TransactionalEventListener} actually receives it (a no-active-tx publish is
 * dropped — gotcha). The composer does not self-invoke any other {@code @Transactional} method, so
 * no {@code @Lazy self} indirection is needed.
 *
 * <p><b>Decision-log</b> writes are null-tolerant via {@code ObjectProvider<DecisionLogService>}
 * (planner-01l owns the writer); each row chains {@code parentDecisionId} to the previous.
 *
 * <p><b>Idempotency-Key</b> is an in-memory Caffeine cache keyed by {@code (userId,
 * idempotencyKey)} with a 5-minute TTL holding the produced plan id (NOT a DB table for v1, per
 * ticket §Gotchas). The controller resolves the cached plan to a {@code PlanDto} and returns 200.
 */
@Component
public class PlanComposer {

  private static final Logger log = LoggerFactory.getLogger(PlanComposer.class);

  static final String QUALITY_WARNING_STAGE_D_UNAVAILABLE =
      "Stage D adaptation unavailable; using original recipe selections";

  private final PlanCompositionContextBuilder contextBuilder;
  private final BeamSearchEngine beamSearchEngine;
  private final RollupBuilder rollupBuilder;
  private final StageCInvoker stageCInvoker;
  private final Phase2Augmenter phase2Augmenter;
  private final PlanPersister planPersister;
  private final RefineDirectiveMapper refineDirectiveMapper;
  private final AdaptationService adaptationService;
  private final ApplicationEventPublisher eventPublisher;
  private final org.springframework.beans.factory.ObjectProvider<DecisionLogService>
      decisionLogProvider;
  private final PlannerProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** {@code (userId|idempotencyKey)} -> generated plan id; 5-minute TTL (ticket §Gotchas). */
  private final Cache<String, UUID> idempotencyCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10_000).build();

  PlanComposer(
      PlanCompositionContextBuilder contextBuilder,
      BeamSearchEngine beamSearchEngine,
      RollupBuilder rollupBuilder,
      StageCInvoker stageCInvoker,
      Phase2Augmenter phase2Augmenter,
      PlanPersister planPersister,
      RefineDirectiveMapper refineDirectiveMapper,
      AdaptationService adaptationService,
      ApplicationEventPublisher eventPublisher,
      org.springframework.beans.factory.ObjectProvider<DecisionLogService> decisionLogProvider,
      PlannerProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.contextBuilder = contextBuilder;
    this.beamSearchEngine = beamSearchEngine;
    this.rollupBuilder = rollupBuilder;
    this.stageCInvoker = stageCInvoker;
    this.phase2Augmenter = phase2Augmenter;
    this.planPersister = planPersister;
    this.refineDirectiveMapper = refineDirectiveMapper;
    this.adaptationService = adaptationService;
    this.eventPublisher = eventPublisher;
    this.decisionLogProvider = decisionLogProvider;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Look up a cached plan id for an {@code Idempotency-Key} replay. Returns empty when no recent
   * (&lt;5 min) successful generation matches.
   */
  public Optional<UUID> cachedPlanIdFor(UUID userId, @Nullable String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(idempotencyCache.getIfPresent(cacheKey(userId, idempotencyKey)));
  }

  /**
   * Compose + persist a plan for {@code request}. Single transaction (REQUIRED). Returns the
   * persisted {@code GENERATED} plan's id (the controller maps it to a {@code PlanDto} via the read
   * service — the {@code Plan} entity must not cross the {@code api} boundary, ArchUnit).
   *
   * @param request the generate request
   * @param requestUserId resolved server-side caller id
   * @param idempotencyKey optional client de-dupe key (cached on success)
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public UUID compose(
      GeneratePlanRequest request, UUID requestUserId, @Nullable String idempotencyKey) {
    UUID traceId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    long startNanos = System.nanoTime();

    UUID decisionId =
        writeDecisionLog(
            traceId,
            null,
            "plan_generation_start",
            request.householdId(),
            requestUserId,
            inputs(request, traceId),
            null,
            "Composer entry",
            1,
            null);

    // Stage A context.
    PlanCompositionContext context =
        contextBuilder.build(request, requestUserId, traceId, decisionId);

    boolean qualityWarning = false;

    // Stage A — beam search.
    BeamSearchConfig config =
        new BeamSearchConfig(
            properties.beamWidth(), properties.topN(), properties.maxPoolPerSlot());
    BeamSearchOutcome outcome = beamSearchEngine.search(context, config);
    List<CandidatePlan> candidates = outcome.candidates();
    if (outcome.degradedToGreedy()) {
      qualityWarning = true;
    }
    UUID stageADecision =
        writeDecisionLog(
            traceId,
            decisionId,
            "stage_a_done",
            request.householdId(),
            requestUserId,
            null,
            null,
            "Stage A produced " + candidates.size() + " candidate(s)",
            1,
            null);

    if (candidates.isEmpty()) {
      // No recipe pool / no feasible candidate: persist a minimal quality-warning plan so the
      // HTTP face stays alive ahead of the recipe-search dependency (LLD §Failure Modes).
      CandidatePlan empty =
          new CandidatePlan(UUID.randomUUID(), request.weekStartDate(), List.of(), null);
      Plan plan =
          planPersister.persist(empty, request, context, planId, emptyRollup(), false, true);
      finishDecisionLog(
          traceId, stageADecision, request, requestUserId, plan, startNanos, "no-candidates");
      publishGenerated(plan, traceId);
      cacheIdempotent(requestUserId, idempotencyKey, plan.getId());
      return plan.getId();
    }

    // Stage B — per-candidate rollups.
    List<RollupSummaryDocument> rollups =
        candidates.stream().map(c -> rollupBuilder.build(c, context)).toList();
    List<CandidatePlanRollupDto> rollupDtos =
        rollups.stream().map(PlanComposer::toRollupDto).toList();

    // Stage C — LLM pick-of-N.
    StageCResult stageC = stageCInvoker.pickOne(candidates, rollupDtos, context, traceId);
    int chosenIndex =
        stageC.chosenIndex() >= 0 && stageC.chosenIndex() < candidates.size()
            ? stageC.chosenIndex()
            : 0;
    CandidatePlan chosen = candidates.get(chosenIndex);
    RollupSummaryDocument chosenRollup = rollups.get(chosenIndex);
    UUID stageCDecision =
        writeDecisionLog(
            traceId,
            stageADecision,
            "stage_c_done",
            request.householdId(),
            requestUserId,
            null,
            null,
            stageC.reasoning(),
            1,
            null);

    // Phase 2 — augment + emit refine-directive proposals.
    var phase2 = phase2Augmenter.augment(chosen, rollupDtos.get(chosenIndex), context, traceId);
    boolean aiAugmented = !stageC.fallback() && !phase2.applied().isEmpty();
    UUID phase2Decision =
        writeDecisionLog(
            traceId,
            stageCDecision,
            "phase_2_done",
            request.householdId(),
            requestUserId,
            null,
            null,
            "Phase 2 applied "
                + phase2.applied().size()
                + " augmentation(s); "
                + phase2.emittedDirectives().size()
                + " refine-directive(s) emitted",
            1,
            null);

    // Stage D — route emitted refine-directive proposals to the adaptation pipeline. The
    // current Phase-2 impl emits an empty directive list (it defers the cross-module assembly to
    // the composer per RefineDirectiveDto's javadoc); the routing is exercised end-to-end by
    // PlanComposerIT which stubs AdaptationService. We bound the routing to the configured
    // max-refine-directive budget.
    List<SlotAssignment> mutatedAssignments = new ArrayList<>(chosen.assignments());
    int directivesRouted = 0;
    for (var directive : phase2.emittedDirectives()) {
      if (directivesRouted >= properties.maxRefineDirectives()) {
        break;
      }
      directivesRouted++;
      int slotIdx = indexOfSlot(mutatedAssignments, directive.targetSlotId());
      if (slotIdx < 0) {
        log.info("Refine-directive targets unknown slot {}; skipping", directive.targetSlotId());
        continue;
      }
      SlotAssignment target = mutatedAssignments.get(slotIdx);
      var proposal =
          new com.example.mealprep.planner.api.dto.RefineDirectiveProposal(
              directive.kind(),
              directive.targetSlotId(),
              directive.fromKey(),
              directive.toKey(),
              null,
              null,
              directive.description());
      PlanTimeRefineDirectiveRequest stageDRequest =
          refineDirectiveMapper.toRequest(
              proposal,
              target.recipeId(),
              requestUserId,
              planId,
              target.slotId(),
              phase2Decision,
              context);
      try {
        AdaptationResultDto result = adaptationService.runPlanTimeRefineJob(stageDRequest);
        applyAdaptationResult(mutatedAssignments, slotIdx, result);
      } catch (AdaptationAiUnavailableException ex) {
        // Block-and-prompt fallback: log WARN, skip this directive, keep original recipe.
        log.warn(
            "Stage D adaptation unavailable for slot {} (trace {}): {}; leaving original recipe",
            target.slotId(),
            traceId,
            ex.getMessage());
        qualityWarning = true;
      }
    }

    CandidatePlan mutated =
        new CandidatePlan(
            chosen.candidateId(), chosen.weekStartDate(), mutatedAssignments, chosen.scoreResult());

    Plan plan =
        planPersister.persist(
            mutated, request, context, planId, chosenRollup, aiAugmented, qualityWarning);

    finishDecisionLog(traceId, phase2Decision, request, requestUserId, plan, startNanos, "ok");
    publishGenerated(plan, traceId);
    cacheIdempotent(requestUserId, idempotencyKey, plan.getId());
    return plan.getId();
  }

  // ---- Stage-D result application -------------------------------------------------------------

  private void applyAdaptationResult(
      List<SlotAssignment> assignments, int slotIdx, AdaptationResultDto result) {
    SlotAssignment a = assignments.get(slotIdx);
    AdaptationClassification c = result.classification();
    if (c == AdaptationClassification.NO_CHANGE) {
      log.info("Adaptation NO_CHANGE for slot {}; leaving recipe unchanged", a.slotId());
      return;
    }
    if (c == AdaptationClassification.VERSION && result.versionIdCreated().isPresent()) {
      UUID newId = result.versionIdCreated().orElseThrow();
      assignments.set(slotIdx, withRecipe(a, newId, newId, a.recipeBranchId()));
    } else if (c == AdaptationClassification.BRANCH && result.branchIdCreated().isPresent()) {
      UUID newBranch = result.branchIdCreated().orElseThrow();
      assignments.set(slotIdx, withRecipe(a, newBranch, a.recipeVersionId(), newBranch));
    } else if (c == AdaptationClassification.SUBSTITUTION) {
      // Substitution overlay: the recipe id stays; the substitution is auditable on the
      // adaptation side. No slot-level appliedSubstitutions field exists on SlotAssignment in
      // this codebase — recorded via the decision log / adaptation trace instead.
      log.info(
          "Adaptation SUBSTITUTION for slot {} (substitutionId={})",
          a.slotId(),
          result.substitutionIdCreated().map(UUID::toString).orElse("none"));
    }
  }

  private static SlotAssignment withRecipe(
      SlotAssignment a, UUID recipeId, UUID versionId, UUID branchId) {
    return new SlotAssignment(
        a.dayId(),
        a.slotId(),
        a.slotIndex(),
        a.onDate(),
        a.kind(),
        recipeId,
        versionId,
        branchId,
        a.servings(),
        a.pinned());
  }

  private static int indexOfSlot(List<SlotAssignment> assignments, UUID slotId) {
    for (int i = 0; i < assignments.size(); i++) {
      if (assignments.get(i).slotId() != null && assignments.get(i).slotId().equals(slotId)) {
        return i;
      }
    }
    return -1;
  }

  // ---- rollup adaptation (planner RollupSummaryDocument -> nutrition CandidatePlanRollupDto) ---

  private static CandidatePlanRollupDto toRollupDto(RollupSummaryDocument doc) {
    List<CandidateDailyRollupDto> perDay = new ArrayList<>();
    for (DailyRollupDocument d : doc.daily()) {
      perDay.add(
          new CandidateDailyRollupDto(
              d.date(),
              ActivityLevel.LIGHT_ACTIVITY,
              d.kcal(),
              d.proteinG(),
              d.carbsG(),
              d.fatG(),
              d.fibreG(),
              Map.of()));
    }
    if (perDay.isEmpty()) {
      java.time.LocalDate today = java.time.LocalDate.now();
      perDay.add(
          new CandidateDailyRollupDto(
              today,
              ActivityLevel.LIGHT_ACTIVITY,
              0,
              java.math.BigDecimal.ZERO,
              java.math.BigDecimal.ZERO,
              java.math.BigDecimal.ZERO,
              java.math.BigDecimal.ZERO,
              Map.of()));
    }
    return new CandidatePlanRollupDto(
        perDay.get(0).date(), perDay.get(perDay.size() - 1).date(), perDay);
  }

  private static RollupSummaryDocument emptyRollup() {
    return new RollupSummaryDocument(
        List.of(),
        new com.example.mealprep.planner.api.dto.WeeklyRollupDocument(
            0,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            0,
            java.math.BigDecimal.ZERO,
            0,
            List.of()));
  }

  // ---- events + idempotency ------------------------------------------------------------------

  private void publishGenerated(Plan plan, UUID traceId) {
    eventPublisher.publishEvent(
        new PlanGeneratedEvent(
            plan.getId(),
            plan.getHouseholdId(),
            plan.getWeekStartDate(),
            plan.getGeneration(),
            plan.getTriggerKind(),
            plan.getTriggerEventId(),
            plan.getDecisionId(),
            plan.isColdStart(),
            plan.isAiAugmented(),
            plan.isQualityWarning(),
            traceId,
            clock.instant()));
  }

  private void cacheIdempotent(UUID userId, @Nullable String idempotencyKey, UUID planId) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      idempotencyCache.put(cacheKey(userId, idempotencyKey), planId);
    }
  }

  private static String cacheKey(UUID userId, String idempotencyKey) {
    return userId + "|" + idempotencyKey;
  }

  // ---- decision log (null-tolerant) ----------------------------------------------------------

  private ObjectNode inputs(GeneratePlanRequest request, UUID traceId) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("householdId", request.householdId().toString());
    node.put("weekStartDate", request.weekStartDate().toString());
    node.put("traceId", traceId.toString());
    return node;
  }

  private void finishDecisionLog(
      UUID traceId,
      @Nullable UUID parent,
      GeneratePlanRequest request,
      UUID requestUserId,
      Plan plan,
      long startNanos,
      String outcome) {
    ObjectNode out = objectMapper.createObjectNode();
    out.put("planId", plan.getId().toString());
    out.put("status", plan.getStatus().name());
    out.put("qualityWarning", plan.isQualityWarning());
    out.put("outcome", outcome);
    int durationMs = (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    writeDecisionLog(
        traceId,
        parent,
        "plan_generation_complete",
        request.householdId(),
        requestUserId,
        null,
        out,
        "Composer exit",
        1,
        durationMs);
  }

  @Nullable
  private UUID writeDecisionLog(
      UUID traceId,
      @Nullable UUID parentDecisionId,
      String kind,
      UUID scopeId,
      UUID actorUserId,
      @Nullable ObjectNode inputs,
      @Nullable ObjectNode outputs,
      String reasoning,
      int iteration,
      @Nullable Integer durationMs) {
    DecisionLogService writer = decisionLogProvider.getIfAvailable();
    if (writer == null) {
      log.debug("DecisionLogService unavailable (planner-01l not merged); skipping {} row", kind);
      return null;
    }
    try {
      return writer.write(
          new DecisionLogWriteRequest(
              traceId,
              parentDecisionId,
              kind,
              scopeId,
              DecisionLogScale.WEEK,
              "user",
              actorUserId,
              inputs,
              null,
              outputs,
              reasoning,
              null,
              iteration,
              durationMs));
    } catch (RuntimeException ex) {
      log.warn("Failed to write {} decision-log row: {}", kind, ex.toString());
      return null;
    }
  }
}
