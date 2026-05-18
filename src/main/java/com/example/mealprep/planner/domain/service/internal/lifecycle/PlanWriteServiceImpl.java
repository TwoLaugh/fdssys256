package com.example.mealprep.planner.domain.service.internal.lifecycle;

import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.example.mealprep.planner.api.mapper.ReoptSuggestionMapper;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.MealSlotRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.PlanWriteService;
import com.example.mealprep.planner.event.PlanAbandonedEvent;
import com.example.mealprep.planner.event.PlanAcceptedEvent;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PlanRejectedEvent;
import com.example.mealprep.planner.event.PlanSupersededEvent;
import com.example.mealprep.planner.exception.MealSlotNotFoundException;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.PlanNotReoptableException;
import com.example.mealprep.planner.exception.ReoptSuggestionNotFoundException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single impl of {@link PlanWriteService} (planner-01j). Each lifecycle op is
 * {@code @Transactional} (REQUIRED) and transitions the plan via {@link PlanStateMachine}, then
 * publishes the corresponding {@code PlannerEvent} <b>inside</b> the transactional body so an
 * {@code AFTER_COMMIT} listener actually receives it (gotcha).
 *
 * <p>{@code rejectPlan} is idempotent per LLD §Flow 3 (re-rejecting a {@code REJECTED} plan returns
 * 200, no exception). All other illegal transitions raise {@code
 * InvalidPlanStateTransitionException} (mapped to 409 by {@code PlannerExceptionHandler}).
 */
@Service
public class PlanWriteServiceImpl implements PlanWriteService {

  private static final Logger log = LoggerFactory.getLogger(PlanWriteServiceImpl.class);

  private final PlanRepository planRepository;
  private final MealSlotRepository mealSlotRepository;
  private final MealPrepPlanReoptSuggestionRepository suggestionRepository;
  private final PlanStateMachine stateMachine;
  private final ApplicationEventPublisher eventPublisher;
  private final ReoptSuggestionMapper reoptSuggestionMapper;
  private final Clock clock;

  public PlanWriteServiceImpl(
      PlanRepository planRepository,
      MealSlotRepository mealSlotRepository,
      MealPrepPlanReoptSuggestionRepository suggestionRepository,
      PlanStateMachine stateMachine,
      ApplicationEventPublisher eventPublisher,
      ReoptSuggestionMapper reoptSuggestionMapper,
      Clock clock) {
    this.planRepository = planRepository;
    this.mealSlotRepository = mealSlotRepository;
    this.suggestionRepository = suggestionRepository;
    this.stateMachine = stateMachine;
    this.eventPublisher = eventPublisher;
    this.reoptSuggestionMapper = reoptSuggestionMapper;
    this.clock = clock;
  }

  @Override
  @Transactional
  public UUID acceptPlan(UUID planId) {
    Plan plan = load(planId);
    stateMachine.assertPlanTransitionAllowed(plan.getStatus(), PlanStatus.ACTIVE);
    plan.setStatus(PlanStatus.ACTIVE);
    plan.setAcceptedAt(clock.instant());
    planRepository.save(plan);
    eventPublisher.publishEvent(
        new PlanAcceptedEvent(
            plan.getId(),
            plan.getHouseholdId(),
            plan.getWeekStartDate(),
            plan.getTraceId(),
            clock.instant()));
    return plan.getId();
  }

  @Override
  @Transactional
  public UUID rejectPlan(UUID planId, String reason) {
    Plan plan = load(planId);
    if (plan.getStatus() == PlanStatus.REJECTED) {
      // Idempotent no-op (LLD §Flow 3).
      return plan.getId();
    }
    stateMachine.assertPlanTransitionAllowed(plan.getStatus(), PlanStatus.REJECTED);
    plan.setStatus(PlanStatus.REJECTED);
    plan.setRejectedReason(reason);
    plan.setRejectedAt(clock.instant());
    planRepository.save(plan);
    eventPublisher.publishEvent(
        new PlanRejectedEvent(
            plan.getId(),
            plan.getHouseholdId(),
            plan.getWeekStartDate(),
            reason,
            plan.getTraceId(),
            clock.instant()));
    return plan.getId();
  }

  @Override
  @Transactional
  public UUID abandonPlan(UUID planId, String reason) {
    Plan plan = load(planId);
    stateMachine.assertPlanTransitionAllowed(plan.getStatus(), PlanStatus.ABANDONED);
    plan.setStatus(PlanStatus.ABANDONED);
    plan.setAbandonedReason(reason);
    plan.setAbandonedAt(clock.instant());
    planRepository.save(plan);
    eventPublisher.publishEvent(
        new PlanAbandonedEvent(
            plan.getId(),
            plan.getHouseholdId(),
            plan.getWeekStartDate(),
            reason,
            plan.getTraceId(),
            clock.instant()));
    return plan.getId();
  }

  @Override
  @Transactional
  public UUID revertPlan(UUID planId) {
    Plan current = load(planId);
    // Revert is only meaningful from an ACTIVE plan (LLD §Flow 4). Any other state is a
    // not-reoptable error (400).
    if (current.getStatus() != PlanStatus.ACTIVE) {
      throw new PlanNotReoptableException(planId, current.getStatus());
    }
    Plan copy = copyForward(current);
    stateMachine.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED);
    copy.setStatus(PlanStatus.GENERATED);
    planRepository.save(copy);

    stateMachine.assertPlanTransitionAllowed(current.getStatus(), PlanStatus.SUPERSEDED);
    current.setStatus(PlanStatus.SUPERSEDED);
    planRepository.save(current);

    eventPublisher.publishEvent(
        new PlanSupersededEvent(
            current.getId(),
            copy.getId(),
            current.getHouseholdId(),
            current.getWeekStartDate(),
            current.getTraceId(),
            clock.instant()));
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
    return copy.getId();
  }

  @Override
  @Transactional
  public UUID changeSlotState(UUID planId, UUID slotId, SlotState newState) {
    MealSlot slot =
        mealSlotRepository
            .findByIdAndPlanId(slotId, planId)
            .orElseThrow(() -> new MealSlotNotFoundException(slotId));
    stateMachine.assertSlotTransitionAllowed(slot.getState(), newState);
    slot.setState(newState);
    stateMachine.derivePinnedReason(newState).ifPresent(slot::setPinnedReason);
    mealSlotRepository.save(slot);
    return planId;
  }

  @Override
  @Transactional
  public PlanReoptSuggestionDto acceptReoptSuggestion(UUID planId, UUID suggestionId) {
    MealPrepPlanReoptSuggestion suggestion =
        suggestionRepository
            .findById(suggestionId)
            .orElseThrow(() -> new ReoptSuggestionNotFoundException(suggestionId));
    if (!suggestion.getPlanId().equals(planId)) {
      throw new ReoptSuggestionNotFoundException(suggestionId);
    }
    if (suggestion.getStatus() == ReoptSuggestionStatus.ACCEPTED) {
      return reoptSuggestionMapper.toPlanReoptDto(suggestion); // idempotent
    }
    Plan current = load(planId);
    if (current.getStatus() != PlanStatus.ACTIVE && current.getStatus() != PlanStatus.GENERATED) {
      throw new PlanNotReoptableException(planId, current.getStatus());
    }

    // Apply the proposed slot changes onto a fresh GENERATED generation copy of the plan.
    Plan copy = copyForward(current);
    Map<UUID, ProposedSlotChange> changeBySlotId = new HashMap<>();
    if (suggestion.getProposedAssignments() != null) {
      for (ProposedSlotChange c : suggestion.getProposedAssignments().changes()) {
        changeBySlotId.put(c.slotId(), c);
      }
    }
    // The copy's slot ids are freshly minted; we map by (dayDate, slotIndex) back to the
    // original slot, then apply any change keyed on the ORIGINAL slot id.
    Map<String, MealSlot> originalByKey = new HashMap<>();
    for (Day d : current.getDays()) {
      for (MealSlot s : d.getSlots()) {
        originalByKey.put(d.getOnDate() + "#" + s.getSlotIndex(), s);
      }
    }
    for (Day d : copy.getDays()) {
      for (MealSlot s : d.getSlots()) {
        MealSlot original = originalByKey.get(d.getOnDate() + "#" + s.getSlotIndex());
        if (original == null) {
          continue;
        }
        ProposedSlotChange change = changeBySlotId.get(original.getId());
        if (change != null && change.newRecipeId() != null) {
          ScheduledRecipe sr = s.getScheduledRecipe();
          if (sr == null) {
            sr =
                ScheduledRecipe.builder()
                    .id(UUID.randomUUID())
                    .slot(s)
                    .recipeId(change.newRecipeId())
                    .recipeVersionId(
                        change.newRecipeVersionId() != null
                            ? change.newRecipeVersionId()
                            : change.newRecipeId())
                    .recipeBranchId(
                        change.newRecipeBranchId() != null
                            ? change.newRecipeBranchId()
                            : change.newRecipeId())
                    .servings(change.newServings() > 0 ? change.newServings() : 1)
                    .phase2Addition(false)
                    .build();
            s.setScheduledRecipe(sr);
          } else {
            sr.setRecipeId(change.newRecipeId());
            if (change.newRecipeVersionId() != null) {
              sr.setRecipeVersionId(change.newRecipeVersionId());
            }
            if (change.newRecipeBranchId() != null) {
              sr.setRecipeBranchId(change.newRecipeBranchId());
            }
            if (change.newServings() > 0) {
              sr.setServings(change.newServings());
            }
          }
        }
      }
    }
    stateMachine.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED);
    copy.setStatus(PlanStatus.GENERATED);
    planRepository.save(copy);

    stateMachine.assertPlanTransitionAllowed(current.getStatus(), PlanStatus.SUPERSEDED);
    current.setStatus(PlanStatus.SUPERSEDED);
    planRepository.save(current);

    suggestion.setStatus(ReoptSuggestionStatus.ACCEPTED);
    suggestionRepository.save(suggestion);

    eventPublisher.publishEvent(
        new PlanSupersededEvent(
            current.getId(),
            copy.getId(),
            current.getHouseholdId(),
            current.getWeekStartDate(),
            suggestion.getTraceId(),
            clock.instant()));
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
            suggestion.getTraceId(),
            clock.instant()));
    log.info(
        "Accepted re-opt suggestion {} for plan {} -> new generation {}",
        suggestionId,
        planId,
        copy.getId());
    return reoptSuggestionMapper.toPlanReoptDto(suggestion);
  }

  @Override
  @Transactional
  public PlanReoptSuggestionDto rejectReoptSuggestion(UUID planId, UUID suggestionId) {
    MealPrepPlanReoptSuggestion suggestion =
        suggestionRepository
            .findById(suggestionId)
            .orElseThrow(() -> new ReoptSuggestionNotFoundException(suggestionId));
    if (!suggestion.getPlanId().equals(planId)) {
      throw new ReoptSuggestionNotFoundException(suggestionId);
    }
    if (suggestion.getStatus() != ReoptSuggestionStatus.REJECTED) {
      suggestion.setStatus(ReoptSuggestionStatus.REJECTED);
      suggestionRepository.save(suggestion);
    }
    return reoptSuggestionMapper.toPlanReoptDto(suggestion);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private Plan load(UUID planId) {
    Plan plan =
        planRepository.findById(planId).orElseThrow(() -> new PlanNotFoundException(planId));
    // Touch children inside the tx so a downstream PlanDto map (in the controller's same request)
    // does not LazyInitializationException with OSIV disabled.
    plan.getDays().forEach(d -> d.getSlots().forEach(MealSlot::getScheduledRecipe));
    return plan;
  }

  /**
   * Deep-copy the plan's day/slot/scheduled-recipe graph into a new {@code DRAFT} plan with {@code
   * generation = current + 1} and {@code replacesPlanId = current.id}. Freshly minted ids
   * throughout.
   */
  private Plan copyForward(Plan current) {
    Plan copy =
        Plan.builder()
            .id(UUID.randomUUID())
            .householdId(current.getHouseholdId())
            .weekStartDate(current.getWeekStartDate())
            .generation(current.getGeneration() + 1)
            .replacesPlanId(current.getId())
            .status(PlanStatus.DRAFT)
            .triggerKind(TriggerKind.MID_WEEK_REOPT)
            .qualityWarning(current.isQualityWarning())
            .coldStart(false)
            .aiAugmented(current.isAiAugmented())
            .traceId(UUID.randomUUID())
            .decisionId(UUID.randomUUID())
            .scoreBreakdown(current.getScoreBreakdown())
            .rollupSummary(current.getRollupSummary())
            .days(new ArrayList<>())
            .build();
    for (Day d : current.getDays()) {
      Day nd =
          Day.builder()
              .id(UUID.randomUUID())
              .plan(copy)
              .onDate(d.getOnDate())
              .notes(d.getNotes())
              .slots(new ArrayList<>())
              .build();
      for (MealSlot s : d.getSlots()) {
        MealSlot ns =
            MealSlot.builder()
                .id(UUID.randomUUID())
                .day(nd)
                .plan(copy)
                .slotIndex(s.getSlotIndex())
                .kind(s.getKind())
                .label(s.getLabel())
                .timeBudgetMin(s.getTimeBudgetMin())
                .shared(s.isShared())
                .eaters(new ArrayList<>(s.getEaters()))
                .state(SlotState.PLANNED)
                .build();
        ScheduledRecipe sr = s.getScheduledRecipe();
        if (sr != null) {
          ns.setScheduledRecipe(
              ScheduledRecipe.builder()
                  .id(UUID.randomUUID())
                  .slot(ns)
                  .recipeId(sr.getRecipeId())
                  .recipeVersionId(sr.getRecipeVersionId())
                  .recipeBranchId(sr.getRecipeBranchId())
                  .servings(sr.getServings())
                  .phase2Addition(sr.isPhase2Addition())
                  .build());
        }
        nd.getSlots().add(ns);
      }
      copy.getDays().add(nd);
    }
    return copy;
  }
}
