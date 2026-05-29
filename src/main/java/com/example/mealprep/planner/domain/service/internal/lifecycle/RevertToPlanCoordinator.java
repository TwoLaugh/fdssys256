package com.example.mealprep.planner.domain.service.internal.lifecycle;

import com.example.mealprep.core.lock.LeaseHandle;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.planner.api.dto.RevertToPlanRequest;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PlanSupersededEvent;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.RevertTargetNotInHistoryException;
import com.example.mealprep.planner.security.PlannerAuth;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revert-to-a-chosen-historical-plan orchestrator (planner-5, LLD §Flow 4).
 *
 * <p>Replaces the earlier clone-active {@code revertPlan(UUID)} behaviour: revert now means "copy
 * the content of an arbitrary historical plan the user picks onto a new active generation", per the
 * HLD use-case "browse historical plans … revert to plan version N" ([design/meal-planner.md §Plan
 * history and revert]).
 *
 * <h2>Flow (LLD §Flow 4)</h2>
 *
 * <ol>
 *   <li>Acquire the start-of-revert single-flight lease for {@code (household, week)}.
 *   <li>Load the {@code targetHistoricalPlan} by id and validate it belongs to the caller's
 *       household → {@link RevertTargetNotInHistoryException} (422) otherwise. Target-not-found →
 *       {@link PlanNotFoundException} (404).
 *   <li>Load the current active plan for the same {@code (household, week)} (may be {@code null} if
 *       none is active).
 *   <li>Copy the target's day/slot/scheduled-recipe graph onto a fresh generation (new ids).
 *   <li>Re-run {@link HardConstraintFilterService} over every copied scheduled recipe and strip any
 *       now-infeasible recipe (a newly-banned allergen / diet) — the safety-relevant step.
 *   <li>Refill the emptied slots with a constraint-passing candidate from the recipe catalogue.
 *   <li>Persist the new {@code GENERATED} plan, supersede the prior active, publish both events.
 * </ol>
 *
 * <h2>Transaction semantics (Tier-1 AI-outside-tx rule, mirrors {@code PlanComposer})</h2>
 *
 * <p>{@link #revertToPlan} is <b>not</b> {@code @Transactional}. The re-filter, the catalogue
 * refill, and the {@code Phase2Augmenter}-style augmentation are read / AI work that must not hold
 * a DB connection across their latency window (planner #193/#194 — AI/augmentation must NOT run
 * inside a persistence transaction). Only the final supersede + persist + publish — the single DB
 * write boundary — runs inside a short {@code @Transactional(REQUIRED)} block ({@link
 * #persistAndPublish}), reached through the {@link #self} Spring proxy so the advice fires. The
 * target-plan hydration runs in its own {@code @Transactional(readOnly = true)} method ({@link
 * #hydrateTarget}).
 */
@Component
public class RevertToPlanCoordinator {

  private static final Logger log = LoggerFactory.getLogger(RevertToPlanCoordinator.class);

  private final PlanRepository planRepository;
  private final PlanStateMachine stateMachine;
  private final HardConstraintFilterService hardConstraintFilterService;
  private final RecipeQueryService recipeQueryService;
  private final PlannerAuth plannerAuth;
  private final ApplicationEventPublisher eventPublisher;
  private final DecisionLogWriter decisionLogWriter;
  private final LockService lockService;
  private final PlannerProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  // Self-proxy so the non-transactional revertToPlan() can invoke the @Transactional read +
  // persist methods WITH advice (a same-bean call bypasses the proxy and runs with no tx — the
  // event publish would then be dropped and the lazy graph would not hydrate). @Lazy breaks the
  // construction cycle.
  private final RevertToPlanCoordinator self;

  public RevertToPlanCoordinator(
      PlanRepository planRepository,
      PlanStateMachine stateMachine,
      HardConstraintFilterService hardConstraintFilterService,
      RecipeQueryService recipeQueryService,
      PlannerAuth plannerAuth,
      ApplicationEventPublisher eventPublisher,
      DecisionLogWriter decisionLogWriter,
      LockService lockService,
      PlannerProperties properties,
      ObjectMapper objectMapper,
      Clock clock,
      @Lazy RevertToPlanCoordinator self) {
    this.planRepository = planRepository;
    this.stateMachine = stateMachine;
    this.hardConstraintFilterService = hardConstraintFilterService;
    this.recipeQueryService = recipeQueryService;
    this.plannerAuth = plannerAuth;
    this.eventPublisher = eventPublisher;
    this.decisionLogWriter = decisionLogWriter;
    this.lockService = lockService;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.self = self;
  }

  /**
   * Revert to the chosen historical plan. Returns the NEW active-candidate plan's id. Not
   * {@code @Transactional}; the re-filter / refill / augmentation run outside any tx and the single
   * short write boundary is {@link #persistAndPublish}.
   *
   * @param requestUserId resolved server-side caller id (used for the household-ownership guard and
   *     the per-user hard-constraint re-check)
   * @param request carries {@code targetHistoricalPlanId}
   */
  public UUID revertToPlan(UUID requestUserId, RevertToPlanRequest request) {
    // Step 2 (read-only): hydrate + ownership-guard the target before taking any lock, so a bad
    // target fails fast (404 / 422) without contending the lease.
    TargetSnapshot target = self.hydrateTarget(requestUserId, request.targetHistoricalPlanId());

    // Step 1: single-flight lease for (household, week) — a connection-free committed lease, freed
    // in finally. Holding it across the out-of-tx refill/augmentation pins no DB connection.
    LockKey lockKey = LockKey.forPlanWeek(target.householdId(), target.weekStartDate());
    LeaseHandle lease =
        lockService
            .acquireLease(lockKey, properties.leaseTtl())
            .orElseThrow(
                () ->
                    new com.example.mealprep.planner.exception
                        .ConcurrentGenerationInProgressException(
                        target.householdId(), target.weekStartDate()));
    try {
      // Steps 4-6 (out of tx): copy the target graph, strip now-banned recipes, refill the gaps.
      RevertPlanDraft draft = buildRevertDraft(requestUserId, target);
      // Step 7: single short write boundary — persist + supersede + publish.
      return self.persistAndPublish(target, draft);
    } finally {
      lockService.releaseLease(lease);
    }
  }

  // ---- step 2: hydrate + ownership guard (read-only tx) --------------------------------------

  /**
   * Load the target historical plan, force its lazy day/slot/recipe graph, and validate household
   * ownership. Throws {@link PlanNotFoundException} (404) if the target is missing, {@link
   * RevertTargetNotInHistoryException} (422) if it does not belong to the caller's household. The
   * fully-detached {@link TargetSnapshot} is read outside the tx by the rest of the flow.
   *
   * <p>Ownership guard (LLD §Flow 4 step 2): the planner is household-scoped, so "the caller's
   * household" is any household the caller is a member of. If the caller is NOT a member of the
   * target plan's household, the target is not in this household's history → 422. This is where the
   * previously-defined-but-never-thrown {@link RevertTargetNotInHistoryException} fires. Unlike the
   * other lifecycle endpoints (which carry {@code planId} in the path and let the controller
   * pre-authorise via {@code PlannerAuth.canAccessPlan}), revert takes the target in the request
   * body, so this service-level guard IS the authoritative ownership check.
   */
  @Transactional(readOnly = true)
  public TargetSnapshot hydrateTarget(UUID requestUserId, UUID targetPlanId) {
    Plan target =
        planRepository
            .findById(targetPlanId)
            .orElseThrow(() -> new PlanNotFoundException(targetPlanId));

    UUID householdId = target.getHouseholdId();
    if (!plannerAuth.canAccessHousehold(requestUserId, householdId)) {
      throw new RevertTargetNotInHistoryException(targetPlanId);
    }

    List<SlotSnapshot> slots = new ArrayList<>();
    for (Day day : target.getDays()) {
      for (MealSlot slot : day.getSlots()) {
        ScheduledRecipe sr = slot.getScheduledRecipe();
        slots.add(
            new SlotSnapshot(
                day.getOnDate(),
                day.getNotes(),
                slot.getSlotIndex(),
                slot.getKind(),
                slot.getLabel(),
                slot.getTimeBudgetMin(),
                slot.isShared(),
                new ArrayList<>(slot.getEaters()),
                sr == null ? null : sr.getRecipeId(),
                sr == null ? null : sr.getRecipeVersionId(),
                sr == null ? null : sr.getRecipeBranchId(),
                sr == null ? 0 : sr.getServings()));
      }
    }
    return new TargetSnapshot(
        target.getId(),
        householdId,
        target.getWeekStartDate(),
        target.getStatus(),
        target.getScoreBreakdown(),
        target.getRollupSummary(),
        target.isQualityWarning(),
        target.isAiAugmented(),
        List.copyOf(slots));
  }

  // ---- steps 4-6: build the revert draft (out of tx) -----------------------------------------

  /**
   * Copy the target's slot content onto a draft, strip recipes that now fail the caller's hard
   * constraints (step 5), and refill the emptied slots from the catalogue (step 6). Pure
   * computation — no planner writes; the only collaborators are read-only cross-module reads
   * ({@link HardConstraintFilterService}, {@link RecipeQueryService}).
   */
  private RevertPlanDraft buildRevertDraft(UUID requestUserId, TargetSnapshot target) {
    // Cache recipe lookups so a plan that schedules the same recipe twice loads it once.
    Map<UUID, Optional<RecipeDto>> recipeCache = new HashMap<>();

    List<DraftSlot> draftSlots = new ArrayList<>();
    int stripped = 0;
    int refilled = 0;
    Set<UUID> usedRecipeIds = new HashSet<>();

    for (SlotSnapshot s : target.slots()) {
      DraftSlot draft =
          new DraftSlot(
              s.onDate(),
              s.notes(),
              s.slotIndex(),
              s.kind(),
              s.label(),
              s.timeBudgetMin(),
              s.shared(),
              s.eaters(),
              s.recipeId(),
              s.recipeVersionId(),
              s.recipeBranchId(),
              s.servings() > 0 ? s.servings() : 1);

      if (draft.recipeId() != null) {
        if (recipePassesConstraints(draft.recipeId(), s, recipeCache)) {
          usedRecipeIds.add(draft.recipeId());
        } else {
          // Step 5: now-infeasible (e.g. a recipe the user has since become allergic to). Strip it.
          log.info(
              "Revert: stripping recipe {} from slot (kind={}, date={}) — fails the caller's"
                  + " current hard constraints",
              draft.recipeId(),
              draft.kind(),
              draft.onDate());
          draft = draft.cleared();
          stripped++;
        }
      }

      if (draft.recipeId() == null) {
        // Step 6: refill an empty slot with a constraint-passing candidate from the catalogue.
        Optional<RecipeDto> replacement =
            pickReplacement(requestUserId, draft, usedRecipeIds, recipeCache);
        if (replacement.isPresent()) {
          RecipeDto r = replacement.get();
          RecipeVersionDto body = r.currentVersionBody();
          draft =
              draft.withRecipe(
                  r.id(),
                  body != null && body.id() != null ? body.id() : r.id(),
                  r.currentBranchId() != null ? r.currentBranchId() : r.id(),
                  body != null && body.metadata() != null && body.metadata().servings() > 0
                      ? body.metadata().servings()
                      : draft.servings());
          usedRecipeIds.add(r.id());
          refilled++;
        }
      }
      draftSlots.add(draft);
    }

    log.info(
        "Revert draft from target {} (household {}, week {}): {} slot(s), {} stripped, {} refilled",
        target.targetPlanId(),
        target.householdId(),
        target.weekStartDate(),
        draftSlots.size(),
        stripped,
        refilled);
    return new RevertPlanDraft(List.copyOf(draftSlots), stripped, refilled);
  }

  /** True when the recipe still passes the slot's eaters' current hard constraints. */
  private boolean recipePassesConstraints(
      UUID recipeId, SlotSnapshot slot, Map<UUID, Optional<RecipeDto>> recipeCache) {
    List<String> keys = ingredientKeys(recipeId, recipeCache);
    return slotPasses(slot.shared(), slot.eaters(), keys);
  }

  /**
   * Re-run the same hard-constraint logic the beam search uses ({@code HardFilterRunner}): a shared
   * slot is checked as a household union; a per-person slot must clear for every eater. An empty
   * eaters list passes (nothing to violate), matching the runner's contract.
   */
  private boolean slotPasses(boolean shared, List<UUID> eaters, List<String> keys) {
    if (eaters == null || eaters.isEmpty()) {
      return true;
    }
    if (shared) {
      return hardConstraintFilterService.checkForHousehold(eaters, keys).passes();
    }
    for (UUID eater : eaters) {
      if (!hardConstraintFilterService.check(eater, keys).passes()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Pick a replacement recipe for an emptied slot: the first catalogue candidate (deterministic by
   * recipe id) that matches the slot kind + time budget, passes the slot's hard constraints, and is
   * not already used elsewhere in the plan. Empty when no feasible candidate exists — the slot then
   * ships empty with {@code qualityWarning} (LLD §Failure Modes: "Phase 2 cannot → slot ships
   * empty"). Reuses {@link RecipeQueryService#findPlannableCandidates} — the same catalogue read
   * the generation pool source uses.
   */
  private Optional<RecipeDto> pickReplacement(
      UUID requestUserId,
      DraftSlot slot,
      Set<UUID> usedRecipeIds,
      Map<UUID, Optional<RecipeDto>> recipeCache) {
    List<RecipeDto> candidates =
        recipeQueryService.findPlannableCandidates(requestUserId, properties.maxPoolPerSlot());
    return candidates.stream()
        .filter(Objects::nonNull)
        .filter(r -> r.id() != null && !usedRecipeIds.contains(r.id()))
        .filter(r -> matchesKind(r, slot.kind()))
        .filter(r -> withinTimeBudget(r, slot.timeBudgetMin()))
        .filter(
            r -> {
              List<String> keys = ingredientKeysOf(r);
              recipeCache.put(r.id(), Optional.of(r));
              return slotPasses(slot.shared(), slot.eaters(), keys);
            })
        .min(Comparator.comparing(RecipeDto::id));
  }

  private List<String> ingredientKeys(UUID recipeId, Map<UUID, Optional<RecipeDto>> recipeCache) {
    Optional<RecipeDto> recipe = recipeCache.computeIfAbsent(recipeId, recipeQueryService::getById);
    return recipe.map(this::ingredientKeysOf).orElseGet(List::of);
  }

  private List<String> ingredientKeysOf(RecipeDto recipe) {
    RecipeVersionDto body = recipe.currentVersionBody();
    if (body == null || body.ingredients() == null) {
      return List.of();
    }
    return body.ingredients().stream()
        .map(IngredientDto::ingredientMappingKey)
        .filter(Objects::nonNull)
        .toList();
  }

  private boolean matchesKind(RecipeDto recipe, com.example.mealprep.core.types.SlotKind kind) {
    RecipeVersionDto body = recipe.currentVersionBody();
    if (body == null || body.metadata() == null || body.metadata().mealTypes() == null) {
      return false;
    }
    String target = kind == null ? "" : kind.name().toLowerCase(Locale.ROOT);
    return body.metadata().mealTypes().stream()
        .filter(Objects::nonNull)
        .map(t -> t.toLowerCase(Locale.ROOT))
        .anyMatch(target::equals);
  }

  private boolean withinTimeBudget(RecipeDto recipe, int budgetMin) {
    RecipeVersionDto body = recipe.currentVersionBody();
    if (body == null || body.metadata() == null) {
      return false;
    }
    if (budgetMin <= 0) {
      return true; // no budget set on the slot — accept any (matches the historical content rule)
    }
    double cap = budgetMin * properties.maxTimeOvershootRatio().doubleValue();
    return body.metadata().totalTimeMins() <= Math.round(cap);
  }

  // ---- step 7: persist + supersede + publish (single short tx) -------------------------------

  /**
   * The single DB write boundary (LLD §Flow 4 step 7). One {@code @Transactional(REQUIRED)}: build
   * + persist the new {@code GENERATED} plan from the draft, supersede the current active plan (if
   * any), publish {@code PlanSupersededEvent} (old) + {@code PlanGeneratedEvent} (new) INSIDE the
   * tx so an {@code AFTER_COMMIT} listener receives them, and write the lifecycle audit row.
   * Returns the new plan id. Public so the {@link #self} proxy applies the advice.
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public UUID persistAndPublish(TargetSnapshot target, RevertPlanDraft draft) {
    Plan current =
        planRepository
            .findFirstByHouseholdIdAndWeekStartDateAndStatus(
                target.householdId(), target.weekStartDate(), PlanStatus.ACTIVE)
            .orElse(null);

    int generation =
        1
            + planRepository.countByHouseholdIdAndWeekStartDate(
                target.householdId(), target.weekStartDate());
    boolean qualityWarning =
        target.qualityWarning() || draft.slots().stream().anyMatch(s -> s.recipeId() == null);

    UUID newPlanId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Plan copy =
        Plan.builder()
            .id(newPlanId)
            .householdId(target.householdId())
            .weekStartDate(target.weekStartDate())
            .generation(generation)
            .replacesPlanId(current != null ? current.getId() : target.targetPlanId())
            .status(PlanStatus.DRAFT)
            .triggerKind(TriggerKind.USER_INITIATED)
            .qualityWarning(qualityWarning)
            .coldStart(false)
            .aiAugmented(target.aiAugmented())
            .traceId(traceId)
            .decisionId(UUID.randomUUID())
            .scoreBreakdown(target.scoreBreakdown())
            .rollupSummary(target.rollupSummary())
            .days(new ArrayList<>())
            .build();

    Map<java.time.LocalDate, Day> daysByDate = new java.util.LinkedHashMap<>();
    List<DraftSlot> ordered = new ArrayList<>(draft.slots());
    ordered.sort(Comparator.comparing(DraftSlot::onDate).thenComparingInt(DraftSlot::slotIndex));
    for (DraftSlot ds : ordered) {
      Day day =
          daysByDate.computeIfAbsent(
              ds.onDate(),
              onDate -> {
                Day d =
                    Day.builder()
                        .id(UUID.randomUUID())
                        .plan(copy)
                        .onDate(onDate)
                        .notes(ds.notes())
                        .slots(new ArrayList<>())
                        .build();
                copy.getDays().add(d);
                return d;
              });
      MealSlot slot =
          MealSlot.builder()
              .id(UUID.randomUUID())
              .day(day)
              .plan(copy)
              .slotIndex(ds.slotIndex())
              .kind(ds.kind())
              .label(
                  ds.label() != null ? ds.label() : (ds.kind() != null ? ds.kind().name() : "MEAL"))
              .timeBudgetMin(ds.timeBudgetMin())
              .shared(ds.shared())
              .eaters(new ArrayList<>(ds.eaters()))
              .state(SlotState.PLANNED)
              .build();
      if (ds.recipeId() != null) {
        slot.setScheduledRecipe(
            ScheduledRecipe.builder()
                .id(UUID.randomUUID())
                .slot(slot)
                .recipeId(ds.recipeId())
                .recipeVersionId(
                    ds.recipeVersionId() != null ? ds.recipeVersionId() : ds.recipeId())
                .recipeBranchId(ds.recipeBranchId() != null ? ds.recipeBranchId() : ds.recipeId())
                .servings(ds.servings() > 0 ? ds.servings() : 1)
                .phase2Addition(false)
                .build());
      }
      day.getSlots().add(slot);
    }

    stateMachine.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED);
    copy.setStatus(PlanStatus.GENERATED);
    planRepository.save(copy);

    if (current != null) {
      PlanStatus from = current.getStatus();
      stateMachine.assertPlanTransitionAllowed(from, PlanStatus.SUPERSEDED);
      current.setStatus(PlanStatus.SUPERSEDED);
      planRepository.save(current);
      logTransition(current, from, PlanStatus.SUPERSEDED);
      eventPublisher.publishEvent(
          new PlanSupersededEvent(
              current.getId(),
              copy.getId(),
              current.getHouseholdId(),
              current.getWeekStartDate(),
              current.getTraceId(),
              clock.instant()));
    }

    logRevert(copy, target, draft);
    eventPublisher.publishEvent(
        new PlanGeneratedEvent(
            copy.getId(),
            copy.getHouseholdId(),
            copy.getWeekStartDate(),
            copy.getGeneration(),
            copy.getTriggerKind(),
            copy.getTriggerEventId(),
            copy.getDecisionId(),
            copy.isColdStart(),
            copy.isAiAugmented(),
            copy.isQualityWarning(),
            copy.getTraceId(),
            clock.instant()));
    log.info(
        "Reverted to historical plan {} -> new generation {} (gen {}), {} stripped, {} refilled",
        target.targetPlanId(),
        copy.getId(),
        generation,
        draft.strippedCount(),
        draft.refilledCount());
    return copy.getId();
  }

  // ---- decision-log -------------------------------------------------------------------------

  private void logTransition(Plan plan, PlanStatus from, PlanStatus to) {
    ObjectNode inputs = objectMapper.createObjectNode();
    inputs.put("planId", plan.getId().toString());
    inputs.put("from", from == null ? null : from.name());
    inputs.put("to", to.name());
    decisionLogWriter.write(
        new DecisionLogEntry(
            PlannerDecisionKind.PLAN_LIFECYCLE_TRANSITION,
            plan.getId(),
            null,
            null,
            plan.getTraceId(),
            inputs,
            objectMapper.createObjectNode().put("status", to.name()),
            from + " -> " + to,
            "user"));
  }

  private void logRevert(Plan copy, TargetSnapshot target, RevertPlanDraft draft) {
    ObjectNode inputs = objectMapper.createObjectNode();
    inputs.put("targetHistoricalPlanId", target.targetPlanId().toString());
    inputs.put("newPlanId", copy.getId().toString());
    ObjectNode outputs = objectMapper.createObjectNode();
    outputs.put("generation", copy.getGeneration());
    outputs.put("strippedRecipes", draft.strippedCount());
    outputs.put("refilledSlots", draft.refilledCount());
    outputs.put("qualityWarning", copy.isQualityWarning());
    decisionLogWriter.write(
        new DecisionLogEntry(
            PlannerDecisionKind.PLAN_LIFECYCLE_TRANSITION,
            copy.getId(),
            null,
            null,
            copy.getTraceId(),
            inputs,
            outputs,
            "Reverted to historical plan " + target.targetPlanId(),
            "user"));
  }

  // ---- value carriers ------------------------------------------------------------------------

  /** Detached snapshot of the target historical plan (read once, used out of tx). */
  public record TargetSnapshot(
      UUID targetPlanId,
      UUID householdId,
      java.time.LocalDate weekStartDate,
      PlanStatus status,
      com.example.mealprep.planner.api.dto.ScoreBreakdownDocument scoreBreakdown,
      com.example.mealprep.planner.api.dto.RollupSummaryDocument rollupSummary,
      boolean qualityWarning,
      boolean aiAugmented,
      List<SlotSnapshot> slots) {}

  /** One detached slot from the target. */
  public record SlotSnapshot(
      java.time.LocalDate onDate,
      String notes,
      int slotIndex,
      com.example.mealprep.core.types.SlotKind kind,
      String label,
      int timeBudgetMin,
      boolean shared,
      List<UUID> eaters,
      UUID recipeId,
      UUID recipeVersionId,
      UUID recipeBranchId,
      int servings) {}

  /** The re-filtered + refilled draft, ready to persist. */
  public record RevertPlanDraft(List<DraftSlot> slots, int strippedCount, int refilledCount) {}

  /** A mutable-by-rebuild draft slot carried through the strip/refill passes. */
  public record DraftSlot(
      java.time.LocalDate onDate,
      String notes,
      int slotIndex,
      com.example.mealprep.core.types.SlotKind kind,
      String label,
      int timeBudgetMin,
      boolean shared,
      List<UUID> eaters,
      UUID recipeId,
      UUID recipeVersionId,
      UUID recipeBranchId,
      int servings) {

    DraftSlot cleared() {
      return new DraftSlot(
          onDate,
          notes,
          slotIndex,
          kind,
          label,
          timeBudgetMin,
          shared,
          eaters,
          null,
          null,
          null,
          servings);
    }

    DraftSlot withRecipe(UUID recipeId, UUID versionId, UUID branchId, int servings) {
      return new DraftSlot(
          onDate,
          notes,
          slotIndex,
          kind,
          label,
          timeBudgetMin,
          shared,
          eaters,
          recipeId,
          versionId,
          branchId,
          servings);
    }
  }
}
