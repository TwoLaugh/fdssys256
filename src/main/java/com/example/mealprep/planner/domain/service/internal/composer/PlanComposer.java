package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
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
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
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
 * <p><b>Decision-log</b> writes go through {@link DecisionLogWriter} (planner-01l). Each stage row
 * chains its {@code parentDecisionId} to the previous stage's id, forming a single connected DAG
 * rooted at the {@code PLAN_GENERATION_START} row. The writer is {@code REQUIRES_NEW} so the audit
 * survives a composer rollback; a failed row write returns {@code null} and the chain degrades to
 * "no parent recorded" rather than aborting generation.
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
  private final ColdStartGate coldStartGate;
  // Self-reference through the Spring proxy so the public (non-transactional) compose() can invoke
  // the @Transactional composeTransactional() WITH proxy advice. Required because the cold-start
  // gate must run BEFORE the composition transaction opens: the gate's runJobSync persists +
  // publishes the discovery-runner event AFTER_COMMIT, so it has to commit on its own (it would
  // never fire while the composer's outer tx stayed open). @Lazy breaks the construction cycle.
  private final PlanComposer self;
  private final BeamSearchEngine beamSearchEngine;
  private final RollupBuilder rollupBuilder;
  private final StageCInvoker stageCInvoker;
  private final Phase2Augmenter phase2Augmenter;
  private final PlanPersister planPersister;
  private final RefineDirectiveMapper refineDirectiveMapper;
  private final AdaptationService adaptationService;
  private final ApplicationEventPublisher eventPublisher;
  private final DecisionLogWriter decisionLogWriter;
  private final PlannerProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** {@code (userId|idempotencyKey)} -> generated plan id; 5-minute TTL (ticket §Gotchas). */
  private final Cache<String, UUID> idempotencyCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10_000).build();

  PlanComposer(
      PlanCompositionContextBuilder contextBuilder,
      ColdStartGate coldStartGate,
      @org.springframework.context.annotation.Lazy PlanComposer self,
      BeamSearchEngine beamSearchEngine,
      RollupBuilder rollupBuilder,
      StageCInvoker stageCInvoker,
      Phase2Augmenter phase2Augmenter,
      PlanPersister planPersister,
      RefineDirectiveMapper refineDirectiveMapper,
      AdaptationService adaptationService,
      ApplicationEventPublisher eventPublisher,
      DecisionLogWriter decisionLogWriter,
      PlannerProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.contextBuilder = contextBuilder;
    this.coldStartGate = coldStartGate;
    this.self = self;
    this.beamSearchEngine = beamSearchEngine;
    this.rollupBuilder = rollupBuilder;
    this.stageCInvoker = stageCInvoker;
    this.phase2Augmenter = phase2Augmenter;
    this.planPersister = planPersister;
    this.refineDirectiveMapper = refineDirectiveMapper;
    this.adaptationService = adaptationService;
    this.eventPublisher = eventPublisher;
    this.decisionLogWriter = decisionLogWriter;
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
   * Compose + persist a plan for {@code request}. Returns the persisted {@code GENERATED} plan's id
   * (the controller maps it to a {@code PlanDto} via the read service — the {@code Plan} entity
   * must not cross the {@code api} boundary, ArchUnit).
   *
   * <p><b>Not {@code @Transactional}.</b> The recipe-pool Tier-2 cold-start gate (LLD §Flow-1 step
   * 5) MUST run BEFORE the composition transaction opens: its {@code DiscoveryService.runJobSync}
   * persists the discovery job and publishes the runner's {@code AFTER_COMMIT} event, which only
   * fires once that write commits. If the gate ran inside the composer's transaction the runner
   * would never start (the event would wait for the outer commit) and the synchronous wait would
   * always time out. So this method: (1) builds a read-only pre-context to size the catalogue, (2)
   * runs the gate (its discovery work commits independently), then (3) delegates the actual
   * composition + persist to {@link #composeTransactional} through the Spring proxy ({@link #self})
   * so the {@code @Transactional} advice applies.
   *
   * @param request the generate request
   * @param requestUserId resolved server-side caller id
   * @param idempotencyKey optional client de-dupe key (cached on success)
   */
  public UUID compose(
      GeneratePlanRequest request, UUID requestUserId, @Nullable String idempotencyKey) {
    boolean coldStart = false;
    if (properties.coldStart().enabled()) {
      // Lightweight read-only pre-context purely to feed the cold-start gate (slot kinds + current
      // pool size). Built outside any planner transaction — the cross-module reads each manage
      // their own readOnly tx. The composition transaction re-reads a fresh context downstream.
      // Only
      // paid for when the gate is enabled (the common steady-state config disables it once the
      // catalogue is mature).
      UUID gateTraceId = UUID.randomUUID();
      PlanCompositionContext preContext =
          contextBuilder.build(request, requestUserId, gateTraceId, null);
      coldStart =
          coldStartGate.fillIfCold(
              requestUserId,
              preContext.slotSkeletons(),
              preContext.recipePool().recipes().size(),
              gateTraceId);
    }
    return self.composeTransactional(request, requestUserId, idempotencyKey, coldStart);
  }

  /**
   * The transactional composition body (Stage A&rarr;D + persist), single transaction (REQUIRED).
   * Re-reads a fresh {@link PlanCompositionContext} so Stage A sees any SYSTEM recipes the
   * cold-start gate just imported. Package-visible (not private) so the {@link #self} proxy can
   * invoke it with transactional advice.
   *
   * @param coldStart whether the cold-start gate fired (threaded onto the persisted plan)
   */
  @Transactional(propagation = Propagation.REQUIRED)
  UUID composeTransactional(
      GeneratePlanRequest request,
      UUID requestUserId,
      @Nullable String idempotencyKey,
      boolean coldStart) {
    UUID traceId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    long startNanos = System.nanoTime();

    // Decision-log row 1 — PLAN_GENERATION_START (trace root: parentDecisionId = null).
    UUID decisionId =
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.PLAN_GENERATION_START,
                planId,
                requestUserId,
                null,
                traceId,
                inputs(request, traceId),
                null,
                "Composer entry",
                "user"));

    // Stage A context — re-read inside the tx so it reflects any cold-start-imported SYSTEM
    // recipes.
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
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.STAGE_A_DONE,
                planId,
                requestUserId,
                decisionId,
                traceId,
                objectMapper.createObjectNode(),
                stageAOutputs(candidates),
                "Stage A produced " + candidates.size() + " candidate(s)",
                "user"));

    if (candidates.isEmpty()) {
      // No recipe pool / no feasible candidate: persist a minimal quality-warning plan so the
      // HTTP face stays alive ahead of the recipe-search dependency (LLD §Failure Modes).
      CandidatePlan empty =
          new CandidatePlan(UUID.randomUUID(), request.weekStartDate(), List.of(), null);
      Plan plan =
          planPersister.persist(
              empty, request, context, planId, emptyRollup(), false, true, coldStart);
      finishDecisionLog(
          traceId, stageADecision, planId, requestUserId, plan, startNanos, "no-candidates");
      publishGenerated(plan, traceId);
      cacheIdempotent(requestUserId, idempotencyKey, plan.getId());
      return plan.getId();
    }

    // Stage B — per-candidate rollups.
    List<RollupSummaryDocument> rollups =
        candidates.stream().map(c -> rollupBuilder.build(c, context)).toList();
    List<CandidatePlanRollupDto> rollupDtos =
        rollups.stream().map(PlanComposer::toRollupDto).toList();
    UUID stageBDecision =
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.STAGE_B_DONE,
                planId,
                requestUserId,
                stageADecision,
                traceId,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode().put("rollupCount", rollups.size()),
                "Stage B rolled up " + rollups.size() + " candidate(s)",
                "user"));

    // Stage C — LLM pick-of-N.
    StageCResult stageC = stageCInvoker.pickOne(candidates, rollupDtos, context, traceId);
    int chosenIndex =
        stageC.chosenIndex() >= 0 && stageC.chosenIndex() < candidates.size()
            ? stageC.chosenIndex()
            : 0;
    CandidatePlan chosen = candidates.get(chosenIndex);
    RollupSummaryDocument chosenRollup = rollups.get(chosenIndex);
    UUID stageCDecision =
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.STAGE_C_DONE,
                planId,
                requestUserId,
                stageBDecision,
                traceId,
                stageCInputs(candidates.size()),
                stageCOutputs(chosenIndex, stageC.reasoning(), qualityWarning),
                stageC.reasoning(),
                "user"));

    // Phase 2 — augment + emit refine-directive proposals.
    var phase2 = phase2Augmenter.augment(chosen, rollupDtos.get(chosenIndex), context, traceId);
    boolean aiAugmented = !stageC.fallback() && !phase2.applied().isEmpty();
    UUID phase2Decision =
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.PHASE_2_DONE,
                planId,
                requestUserId,
                stageCDecision,
                traceId,
                objectMapper.createObjectNode(),
                phase2Outputs(phase2.applied().size(), phase2.emittedDirectives().size()),
                "Phase 2 applied "
                    + phase2.applied().size()
                    + " augmentation(s); "
                    + phase2.emittedDirectives().size()
                    + " refine-directive(s) emitted",
                "user"));

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
              // Cross-module chain (ticket invariant #4): the adaptation pipeline writes its own
              // decision rows with parent_decision_id = this. The shared decision_log table
              // enforces the FK, so phase2Decision MUST be a real persisted row id (it is, unless
              // the row write failed — then the mapper falls back to the trace id).
              phase2Decision,
              context);
      try {
        AdaptationResultDto result = adaptationService.runPlanTimeRefineJob(stageDRequest);
        applyAdaptationResult(mutatedAssignments, slotIdx, result);
        // STAGE_D_OUTCOME — one row per routed directive, parented to PHASE_2 (planner stage) and
        // recording the adaptation job id + classification (invariant #4 / #8).
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.STAGE_D_OUTCOME,
                planId,
                requestUserId,
                phase2Decision,
                traceId,
                objectMapper
                    .createObjectNode()
                    .put("slotId", String.valueOf(target.slotId()))
                    .put("directiveKind", String.valueOf(directive.kind())),
                stageDOutputs(result),
                "Stage D routed directive -> " + result.classification(),
                "user"));
      } catch (AdaptationAiUnavailableException ex) {
        // Block-and-prompt fallback: log WARN, skip this directive, keep original recipe.
        log.warn(
            "Stage D adaptation unavailable for slot {} (trace {}): {}; leaving original recipe",
            target.slotId(),
            traceId,
            ex.getMessage());
        qualityWarning = true;
        decisionLogWriter.write(
            new DecisionLogEntry(
                PlannerDecisionKind.STAGE_D_OUTCOME,
                planId,
                requestUserId,
                phase2Decision,
                traceId,
                objectMapper
                    .createObjectNode()
                    .put("slotId", String.valueOf(target.slotId()))
                    .put("directiveKind", String.valueOf(directive.kind())),
                objectMapper
                    .createObjectNode()
                    .put("adaptationJobId", (String) null)
                    .put("classification", "AI_UNAVAILABLE")
                    .put("versionIdCreated", (String) null),
                "Stage D adaptation unavailable; original recipe retained",
                "user"));
      }
    }

    CandidatePlan mutated =
        new CandidatePlan(
            chosen.candidateId(), chosen.weekStartDate(), mutatedAssignments, chosen.scoreResult());

    Plan plan =
        planPersister.persist(
            mutated,
            request,
            context,
            planId,
            chosenRollup,
            aiAugmented,
            qualityWarning,
            coldStart);

    finishDecisionLog(traceId, phase2Decision, planId, requestUserId, plan, startNanos, "ok");
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

  // ---- decision-log payload builders (ticket invariant #8 shapes) ----------------------------

  private ObjectNode inputs(GeneratePlanRequest request, UUID traceId) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("householdId", request.householdId().toString());
    node.put("weekStartDate", request.weekStartDate().toString());
    node.put("traceId", traceId.toString());
    return node;
  }

  /** {@code STAGE_A_DONE.outputs}: {@code {topNRecipeIds, topNScores, poolSizes}}. */
  private ObjectNode stageAOutputs(List<CandidatePlan> candidates) {
    ObjectNode node = objectMapper.createObjectNode();
    var recipeIds = node.putArray("topNRecipeIds");
    var scores = node.putArray("topNScores");
    ObjectNode poolSizes = node.putObject("poolSizes");
    for (CandidatePlan c : candidates) {
      recipeIds.add(c.candidateId() == null ? null : c.candidateId().toString());
      if (c.scoreResult() != null && c.scoreResult().composite() != null) {
        scores.add(c.scoreResult().composite());
      } else {
        scores.add((java.math.BigDecimal) null);
      }
      for (SlotAssignment a : c.assignments()) {
        if (a.slotId() != null) {
          poolSizes.put(a.slotId().toString(), 1);
        }
      }
    }
    return node;
  }

  /** {@code STAGE_C_DONE.inputs}: {@code {rollupCount, promptVersion}}. */
  private ObjectNode stageCInputs(int rollupCount) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("rollupCount", rollupCount);
    node.put("promptVersion", "planner-stage-c-v1");
    return node;
  }

  /** {@code STAGE_C_DONE.outputs}: {@code {chosenIndex, reasoning, qualityWarnings}}. */
  private ObjectNode stageCOutputs(int chosenIndex, String reasoning, boolean qualityWarning) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("chosenIndex", chosenIndex);
    node.put("reasoning", reasoning);
    var warnings = node.putArray("qualityWarnings");
    if (qualityWarning) {
      warnings.add("stage-a-degraded-to-greedy");
    }
    return node;
  }

  /** {@code PHASE_2_DONE.outputs}: {@code {augmentations, refineDirectiveCount}}. */
  private ObjectNode phase2Outputs(int appliedCount, int refineDirectiveCount) {
    ObjectNode node = objectMapper.createObjectNode();
    node.putArray("augmentations"); // ids not exposed by the current Phase-2 result shape
    node.put("augmentationCount", appliedCount);
    node.put("refineDirectiveCount", refineDirectiveCount);
    return node;
  }

  /**
   * {@code STAGE_D_OUTCOME.outputs}: {@code {adaptationJobId, classification, versionIdCreated}}.
   */
  private ObjectNode stageDOutputs(AdaptationResultDto result) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("adaptationJobId", result.jobId() == null ? null : result.jobId().toString());
    node.put("classification", String.valueOf(result.classification()));
    node.put("versionIdCreated", result.versionIdCreated().map(UUID::toString).orElse(null));
    return node;
  }

  private void finishDecisionLog(
      UUID traceId,
      @Nullable UUID parent,
      UUID planId,
      UUID requestUserId,
      Plan plan,
      long startNanos,
      String outcome) {
    ObjectNode out = objectMapper.createObjectNode();
    out.put("planId", plan.getId().toString());
    out.put("status", plan.getStatus().name());
    out.put("qualityWarning", plan.isQualityWarning());
    out.put("outcome", outcome);
    out.put("durationMs", (int) ((System.nanoTime() - startNanos) / 1_000_000L));
    decisionLogWriter.write(
        new DecisionLogEntry(
            PlannerDecisionKind.PLAN_GENERATION_COMPLETE,
            planId,
            requestUserId,
            parent,
            traceId,
            objectMapper.createObjectNode(),
            out,
            "Composer exit",
            "user"));
  }
}
