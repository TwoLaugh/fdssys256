package com.example.mealprep.planner.domain.service.internal.reopt;

import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchConfig;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.event.ReoptSuggestedEvent;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.PlanNotReoptableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives a mid-week re-optimisation: re-runs Stage A&rarr;B&rarr;C scoped to the remaining
 * (non-pinned) slots of an active plan and materialises the diff as a {@link
 * MealPrepPlanReoptSuggestion} the user can accept/reject (planner-01i). Does NOT mutate the plan
 * in place — 01j's accept endpoint promotes the suggestion.
 *
 * <p>Triggered by 01k's listeners (which run {@code REQUIRES_NEW}); {@link #requestReopt} uses
 * {@code propagation = REQUIRED} so it participates in the listener's transaction (invariant #13).
 * Helper methods carry NO {@code @Transactional}. {@link ReoptSuggestedEvent} is published inside
 * the transactional body so an {@code AFTER_COMMIT} {@code @TransactionalEventListener} actually
 * receives it (a no-active-tx publish would be dropped) — invariant #12.
 *
 * <p>Soft deps not yet merged are constructor-injected {@link Nullable} and degraded gracefully:
 * {@link ReoptContextBuilder} (planner-01j), {@link ReoptStageCInvoker} (planner-01g), {@link
 * DecisionLogService} (core.audit — present, but written null-tolerantly via {@link ObjectProvider}
 * so a future extraction can't brick the path) — invariants #8 / #11.
 */
@Component
class MidWeekReoptCoordinator {

  private static final Logger log = LoggerFactory.getLogger(MidWeekReoptCoordinator.class);

  /** Terminal statuses that cannot be re-optimised (invariant #3). */
  private static final Set<PlanStatus> NON_REOPTABLE =
      EnumSet.of(
          PlanStatus.DRAFT,
          PlanStatus.SUPERSEDED,
          PlanStatus.COMPLETED,
          PlanStatus.REJECTED,
          PlanStatus.ABANDONED);

  /** Suggestion statuses that count against the per-plan budget (invariant #14). */
  private static final Set<ReoptSuggestionStatus> BUDGET_STATUSES =
      EnumSet.of(ReoptSuggestionStatus.PENDING, ReoptSuggestionStatus.REJECTED);

  private static final Duration SUGGESTION_TTL = Duration.ofHours(24);

  private final PlanRepository planRepository;
  private final MealPrepPlanReoptSuggestionRepository suggestionRepository;
  private final BeamSearchEngine beamSearchEngine;
  private final RollupBuilder rollupBuilder;
  private final ApplicationEventPublisher eventPublisher;
  private final PlannerProperties properties;
  private final ObjectMapper objectMapper;
  @Nullable private final ReoptContextBuilder contextBuilder;
  @Nullable private final ReoptStageCInvoker stageCInvoker;
  private final ObjectProvider<DecisionLogService> decisionLogProvider;

  MidWeekReoptCoordinator(
      PlanRepository planRepository,
      MealPrepPlanReoptSuggestionRepository suggestionRepository,
      BeamSearchEngine beamSearchEngine,
      RollupBuilder rollupBuilder,
      ApplicationEventPublisher eventPublisher,
      PlannerProperties properties,
      ObjectMapper objectMapper,
      @Nullable ReoptContextBuilder contextBuilder,
      @Nullable ReoptStageCInvoker stageCInvoker,
      ObjectProvider<DecisionLogService> decisionLogProvider) {
    this.planRepository = planRepository;
    this.suggestionRepository = suggestionRepository;
    this.beamSearchEngine = beamSearchEngine;
    this.rollupBuilder = rollupBuilder;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.contextBuilder = contextBuilder;
    this.stageCInvoker = stageCInvoker;
    this.decisionLogProvider = decisionLogProvider;
  }

  /**
   * Re-optimise the remaining slots of an active plan.
   *
   * @param planId the plan to re-opt
   * @param trigger the classified trigger kind
   * @param triggerEventId the upstream event id (idempotency + trace-chain key)
   * @param traceId the new trace id for this re-opt pass
   * @return the suggestion id if a material re-opt happened, else {@link Optional#empty()} (no
   *     degrees of freedom, no material diff, or rejected-by-budget)
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Optional<UUID> requestReopt(
      UUID planId, ReoptTriggerKind trigger, UUID triggerEventId, UUID traceId) {

    Plan plan =
        planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));

    // (3) Active-plan precondition.
    if (NON_REOPTABLE.contains(plan.getStatus())) {
      throw new PlanNotReoptableException(planId, plan.getStatus());
    }

    // (4) Idempotency on triggerEventId — listener-retry safe.
    Optional<MealPrepPlanReoptSuggestion> existing =
        suggestionRepository.findByPlanIdAndTriggerEventId(planId, triggerEventId);
    if (existing.isPresent()) {
      log.info(
          "Re-opt already computed for plan={} triggerEventId={}; returning suggestion={}",
          planId,
          triggerEventId,
          existing.get().getId());
      return Optional.of(existing.get().getId());
    }

    // (14) Bounded re-opt budget — prevent trigger thrashing.
    long budgetUsed = suggestionRepository.countByPlanIdAndStatusIn(planId, BUDGET_STATUSES);
    if (budgetUsed >= properties.midWeek().maxSuggestionsPerPlan()) {
      log.info(
          "Re-opt budget exhausted for plan={} ({} >= {}); rejecting-by-budget",
          planId,
          budgetUsed,
          properties.midWeek().maxSuggestionsPerPlan());
      writeDecisionLog(
          plan,
          trigger,
          triggerEventId,
          traceId,
          inputs(planId, trigger, triggerEventId, 0, 0),
          outputs(null, List.of(), "rejected-by-budget: " + budgetUsed + " active suggestions"));
      return Optional.empty();
    }

    // (5) Pinning set — read slot.state, NOT plan-level status.
    List<MealSlot> allSlots = orderedSlots(plan);
    PinningSetCalculator pinning = new PinningSetCalculator();
    int lockHours = properties.midWeek().lockHoursBeforeSlot();

    List<MealSlot> pinnedSlots = new ArrayList<>();
    List<MealSlot> nonPinnedSlots = new ArrayList<>();
    for (MealSlot slot : allSlots) {
      long epochDay = slot.getDay().getOnDate().toEpochDay();
      PinnedReason reason = pinning.pinReason(slot, epochDay, lockHours, trigger, Set.<UUID>of());
      if (reason != null) {
        pinnedSlots.add(slot);
      } else {
        nonPinnedSlots.add(slot);
      }
    }

    // (6) No-degrees-of-freedom guard — no decision-log row, no event.
    if (nonPinnedSlots.isEmpty()) {
      log.info(
          "Mid-week re-opt for plan={} has no degrees of freedom (all {} slots pinned); skipping",
          planId,
          allSlots.size());
      return Optional.empty();
    }

    // (7) Narrow the context to only the non-pinned slots. Soft dep — 01j supplies the builder.
    if (contextBuilder == null) {
      log.warn(
          "ReoptContextBuilder unavailable (planner-01j not merged); skipping re-opt for plan={}."
              + " The algorithm is exercised end-to-end by MidWeekReoptFlowIT's inline builder.",
          planId);
      return Optional.empty();
    }
    List<SlotAssignment> pinnedAssignments = toPinnedAssignments(pinnedSlots);
    PlanCompositionContext context =
        contextBuilder.buildForReopt(plan, nonPinnedSlots, pinnedAssignments, traceId);

    // (8) Stage A -> B -> C over the regenerable slots.
    BeamSearchConfig config =
        new BeamSearchConfig(
            properties.beamWidth(), properties.topN(), properties.maxPoolPerSlot());
    BeamSearchOutcome outcome = beamSearchEngine.search(context, config);
    List<CandidatePlan> candidates = outcome.candidates();
    if (candidates.isEmpty()) {
      log.info("Stage-A produced no candidates for re-opt of plan={}; skipping", planId);
      return Optional.empty();
    }
    List<RollupSummaryDocument> rollups =
        candidates.stream().map(c -> rollupBuilder.build(c, context)).toList();
    int chosenIndex = pickViaStageC(candidates, rollups, context, traceId);
    CandidatePlan chosen = candidates.get(chosenIndex);

    // (9) Diff materiality — recipe-identity comparison vs the ORIGINAL plan.
    Map<UUID, ScheduledRecipe> originalBySlotId =
        nonPinnedSlots.stream()
            .filter(s -> s.getScheduledRecipe() != null)
            .collect(Collectors.toMap(MealSlot::getId, MealSlot::getScheduledRecipe));
    Set<UUID> unpinnedSlotIds =
        nonPinnedSlots.stream().map(MealSlot::getId).collect(Collectors.toSet());

    List<ProposedSlotChange> changes = new ArrayList<>();
    for (SlotAssignment a : chosen.assignments()) {
      if (!unpinnedSlotIds.contains(a.slotId())) {
        continue;
      }
      ScheduledRecipe original = originalBySlotId.get(a.slotId());
      UUID oldRecipeId = original == null ? null : original.getRecipeId();
      if (a.recipeId() != null && !a.recipeId().equals(oldRecipeId)) {
        changes.add(
            new ProposedSlotChange(
                a.slotId(),
                oldRecipeId,
                a.recipeId(),
                a.recipeVersionId(),
                a.recipeBranchId(),
                a.servings(),
                "Re-opt (" + trigger + ") selected a better-scoring recipe"));
      }
    }

    if (changes.isEmpty()) {
      log.info(
          "Mid-week re-opt for plan={} produced no material change (Stage-C plan == original);"
              + " no suggestion written",
          planId);
      return Optional.empty();
    }

    // (10) Persist the suggestion aggregate.
    Instant now = Instant.now();
    String summary =
        changes.size() == 1
            ? "1 meal change suggested"
            : changes.size() + " meal changes suggested";
    MealPrepPlanReoptSuggestion suggestion =
        MealPrepPlanReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .planId(planId)
            .triggerKind(trigger)
            .triggerEventId(triggerEventId)
            .traceId(traceId)
            .summary(summary)
            .status(ReoptSuggestionStatus.PENDING)
            .proposedAssignments(ProposedReoptAssignmentsDocument.of(changes))
            .createdAt(now)
            .expiresAt(now.plus(SUGGESTION_TTL))
            .swept(false)
            .build();

    // (11) Decision-log row (null-tolerant until 01l). parent = triggerEventId (15).
    List<UUID> affectedSlotIds = changes.stream().map(ProposedSlotChange::slotId).toList();
    UUID decisionId =
        writeDecisionLog(
            plan,
            trigger,
            triggerEventId,
            traceId,
            inputs(planId, trigger, triggerEventId, pinnedSlots.size(), nonPinnedSlots.size()),
            outputs(suggestion.getId(), affectedSlotIds, summary));
    suggestion.setDecisionId(decisionId);

    suggestionRepository.save(suggestion);

    // (12) Publish AFTER_COMMIT — inside the tx body so the AFTER_COMMIT listener gets it.
    eventPublisher.publishEvent(
        new ReoptSuggestedEvent(
            planId,
            plan.getHouseholdId(),
            plan.getWeekStartDate(),
            suggestion.getId(),
            trigger,
            triggerEventId,
            affectedSlotIds,
            summary,
            traceId,
            now));

    log.info(
        "Mid-week re-opt for plan={} produced suggestion={} with {} change(s)",
        planId,
        suggestion.getId(),
        changes.size());
    return Optional.of(suggestion.getId());
  }

  // ---- helpers (NO @Transactional) ------------------------------------------------------------

  private List<MealSlot> orderedSlots(Plan plan) {
    List<MealSlot> slots = new ArrayList<>();
    for (Day day : plan.getDays()) {
      slots.addAll(day.getSlots());
    }
    slots.sort(
        java.util.Comparator.comparing((MealSlot s) -> s.getDay().getOnDate())
            .thenComparingInt(MealSlot::getSlotIndex));
    return slots;
  }

  private List<SlotAssignment> toPinnedAssignments(List<MealSlot> pinnedSlots) {
    List<SlotAssignment> out = new ArrayList<>(pinnedSlots.size());
    for (MealSlot slot : pinnedSlots) {
      ScheduledRecipe sr = slot.getScheduledRecipe();
      if (sr == null) {
        continue; // an empty pinned slot contributes no assignment to the immutable context
      }
      out.add(
          new SlotAssignment(
              slot.getDay().getId(),
              slot.getId(),
              slot.getSlotIndex(),
              slot.getDay().getOnDate(),
              slot.getKind(),
              sr.getRecipeId(),
              sr.getRecipeVersionId(),
              sr.getRecipeBranchId(),
              sr.getServings(),
              true));
    }
    return out;
  }

  private int pickViaStageC(
      List<CandidatePlan> candidates,
      List<RollupSummaryDocument> rollups,
      PlanCompositionContext context,
      UUID traceId) {
    if (stageCInvoker == null) {
      log.warn(
          "ReoptStageCInvoker unavailable (planner-01g not merged); using deterministic"
              + " top-scored candidate (index 0)");
      return 0;
    }
    try {
      ReoptStageCInvoker.Result result =
          stageCInvoker.pickOne(candidates, rollups, context, traceId);
      int idx = result.chosenIndex();
      if (idx < 0 || idx >= candidates.size()) {
        log.warn(
            "Stage-C returned out-of-range index {} (candidates={}); falling back to index 0",
            idx,
            candidates.size());
        return 0;
      }
      return idx;
    } catch (RuntimeException ex) {
      log.warn(
          "Stage-C pick failed ({}); falling back to deterministic top-scored candidate",
          ex.toString());
      return 0;
    }
  }

  private ObjectNode inputs(
      UUID planId,
      ReoptTriggerKind trigger,
      UUID triggerEventId,
      int pinnedSlotCount,
      int unpinnedSlotCount) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("planId", planId.toString());
    node.put("trigger", trigger.name());
    node.put("triggerEventId", triggerEventId.toString());
    node.put("pinnedSlotCount", pinnedSlotCount);
    node.put("unpinnedSlotCount", unpinnedSlotCount);
    return node;
  }

  private ObjectNode outputs(
      @Nullable UUID suggestionId, List<UUID> affectedSlotIds, String summary) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("suggestionId", suggestionId == null ? null : suggestionId.toString());
    node.set(
        "affectedSlotIds",
        objectMapper.valueToTree(affectedSlotIds.stream().map(UUID::toString).toList()));
    node.put("summary", summary);
    return node;
  }

  /**
   * Writes a {@code kind=mid_week_reopt} decision-log row null-tolerantly (invariant #11). Returns
   * the assigned decision id, or {@code null} if the writer is unavailable (01l not merged).
   */
  @Nullable
  private UUID writeDecisionLog(
      Plan plan,
      ReoptTriggerKind trigger,
      UUID triggerEventId,
      UUID traceId,
      ObjectNode inputs,
      ObjectNode outputs) {
    DecisionLogService writer = decisionLogProvider.getIfAvailable();
    if (writer == null) {
      log.warn(
          "DecisionLogService unavailable (planner-01l not merged); skipping mid_week_reopt"
              + " decision-log row for plan={}",
          plan.getId());
      return null;
    }
    try {
      return writer.write(
          new DecisionLogWriteRequest(
              traceId,
              triggerEventId, // (15) parent_decision_id — chain from the listener's decision id
              "mid_week_reopt",
              plan.getId(),
              DecisionLogScale.WEEK,
              trigger == ReoptTriggerKind.USER ? "user" : "system",
              null,
              inputs,
              null,
              outputs,
              "Mid-week re-opt scoped to non-pinned slots",
              null,
              1,
              null));
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to write mid_week_reopt decision-log row for plan={}: {}",
          plan.getId(),
          ex.toString());
      return null;
    }
  }
}
