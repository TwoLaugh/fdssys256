package com.example.mealprep.planner.domain.service.internal.listeners;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.reopt.MidWeekReoptCoordinator;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.ProvisionChangedEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The planner's reactive surface (planner-01k). Subscribes to the four upstream change-events,
 * applies a per-module materiality filter, and forwards material events to {@link
 * MidWeekReoptCoordinator#requestReopt} (planner-01i) with the right {@link ReoptTriggerKind}.
 * Package-private {@code @Component}; cross-module isolation is asserted by the planner ArchUnit
 * boundary test.
 *
 * <p><strong>Round-7 retro rule (non-negotiable):</strong> every
 * {@code @TransactionalEventListener} method here is {@code @Transactional(propagation =
 * REQUIRES_NEW)}. Plain {@code @Transactional} (the default {@code REQUIRED}) and {@code SUPPORTS}
 * are rejected at context-load on a {@code @TransactionalEventListener}; {@code REQUIRES_NEW} (or
 * {@code NOT_SUPPORTED}) is mandatory. We need a real transaction here because resolving active
 * plans and forcing their lazy day/slot/recipe graph for the materiality filters is a JPA read, and
 * {@link MidWeekReoptCoordinator#requestReopt} (declared {@code REQUIRED}) joins this transaction
 * so its {@code ReoptSuggestedEvent} is published with an active tx (an {@code AFTER_COMMIT}
 * listener silently drops events published with no active tx).
 *
 * <p><strong>LLD-divergence note:</strong> the ticket names the source events {@code
 * PreferenceUpdatedEvent} / {@code HouseholdConfigChangedEvent}; the merged modules publish {@link
 * HardConstraintsUpdatedEvent} (preference-01a) and {@code HouseholdSettingsChangedEvent}
 * (household-01b). We bind the real, merged event types. Likewise {@link ReoptTriggerKind}'s real
 * constants are {@code PROVISIONS / NUTRITION / PREFERENCE / HOUSEHOLD_SETTINGS}, not the ticket's
 * descriptive {@code PROVISION_CHANGED}-style names.
 *
 * <p><strong>Failure isolation (ticket §11):</strong> every listener body is wrapped so an
 * unexpected exception is logged WARN and swallowed — these are {@code AFTER_COMMIT} listeners, the
 * publisher's transaction has already committed and re-throwing only spams a stack trace. {@link
 * AiUnavailableException} (bubbling up from the coordinator's downstream stages) is the one
 * expected case: logged at INFO and swallowed (it is graceful-degrade, not an error).
 *
 * <p><strong>Idempotency (ticket §10):</strong> the upstream events do not carry an explicit
 * event-id, so we derive a deterministic {@code triggerEventId} from the event's stable identity
 * (type + trace + occurredAt + scope). {@link MidWeekReoptCoordinator#requestReopt} deduplicates on
 * {@code (planId, triggerEventId)} — a re-fire of the same event is a safe no-op, so listener
 * retries cannot create duplicate suggestions.
 *
 * <p>Singleton bean — holds NO mutable state (ticket gotcha); all state lives in the events and the
 * DB. The four listeners are independent entry points (no Spring self-invocation), so no
 * {@code @Lazy self} guard is needed.
 */
@Component
class PlannerEventListener {

  private static final Logger log = LoggerFactory.getLogger(PlannerEventListener.class);

  /** Statuses a mid-week re-opt suggestion can attach to (re-optimisable plans). */
  private static final List<PlanStatus> ACTIVE_STATUSES =
      List.of(PlanStatus.GENERATED, PlanStatus.ACTIVE);

  private final PlanRepository planRepository;
  private final MidWeekReoptCoordinator reoptCoordinator;
  private final HouseholdQueryService householdQueryService;
  private final ProvisionMaterialityFilter provisionMaterialityFilter;
  private final NutritionMaterialityFilter nutritionMaterialityFilter;
  private final PreferenceMaterialityFilter preferenceMaterialityFilter;
  private final HouseholdMaterialityFilter householdMaterialityFilter;

  PlannerEventListener(
      PlanRepository planRepository,
      MidWeekReoptCoordinator reoptCoordinator,
      HouseholdQueryService householdQueryService,
      ProvisionMaterialityFilter provisionMaterialityFilter,
      NutritionMaterialityFilter nutritionMaterialityFilter,
      PreferenceMaterialityFilter preferenceMaterialityFilter,
      HouseholdMaterialityFilter householdMaterialityFilter) {
    this.planRepository = planRepository;
    this.reoptCoordinator = reoptCoordinator;
    this.householdQueryService = householdQueryService;
    this.provisionMaterialityFilter = provisionMaterialityFilter;
    this.nutritionMaterialityFilter = nutritionMaterialityFilter;
    this.preferenceMaterialityFilter = preferenceMaterialityFilter;
    this.householdMaterialityFilter = householdMaterialityFilter;
  }

  // ---- Trigger listener 1: provisions-01g -----------------------------------------------------

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onProvisionChanged(ProvisionChangedEvent event) {
    try {
      UUID householdId = resolveHouseholdForUser(event.userId());
      if (householdId == null) {
        log.debug(
            "ProvisionChangedEvent for user {} maps to no household; nothing to re-opt",
            event.userId());
        return;
      }
      UUID triggerEventId = deriveTriggerEventId("provision", event.traceId(), event.scopeId());
      for (Plan plan : activePlans(householdId)) {
        boolean material = provisionMaterialityFilter.isMaterial(event, plan);
        Optional<UUID> suggestionId =
            material
                ? requestReopt(plan, ReoptTriggerKind.PROVISIONS, triggerEventId, event.traceId())
                : Optional.empty();
        log.info(
            "onProvisionChanged plan={} eventType={} material={} suggestionId={}",
            plan.getId(),
            event.getClass().getSimpleName(),
            material,
            suggestionId.orElse(null));
      }
    } catch (AiUnavailableException ex) {
      log.info(
          "onProvisionChanged: AI unavailable during downstream re-opt (graceful-degrade): {}",
          ex.toString());
    } catch (RuntimeException ex) {
      log.warn("onProvisionChanged failed for trace={}: {}", event.traceId(), ex.toString(), ex);
    }
  }

  // ---- Trigger listener 2: nutrition-01h ------------------------------------------------------

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onNutritionIntakeDiverged(NutritionIntakeDivergedEvent event) {
    try {
      UUID householdId = resolveHouseholdForUser(event.userId());
      if (householdId == null) {
        log.debug(
            "NutritionIntakeDivergedEvent for user {} maps to no household; nothing to re-opt",
            event.userId());
        return;
      }
      UUID triggerEventId = deriveTriggerEventId("nutrition", event.traceId(), event.scopeId());
      for (Plan plan : activePlans(householdId)) {
        // The divergence is for a specific day; only plans covering that ISO week are affected.
        if (!weekContains(plan, event.onDate())) {
          continue;
        }
        boolean material = nutritionMaterialityFilter.isMaterial(event, plan);
        Optional<UUID> suggestionId =
            material
                ? requestReopt(plan, ReoptTriggerKind.NUTRITION, triggerEventId, event.traceId())
                : Optional.empty();
        log.info(
            "onNutritionIntakeDiverged plan={} onDate={} material={} suggestionId={}",
            plan.getId(),
            event.onDate(),
            material,
            suggestionId.orElse(null));
      }
    } catch (AiUnavailableException ex) {
      log.info(
          "onNutritionIntakeDiverged: AI unavailable during downstream re-opt"
              + " (graceful-degrade): {}",
          ex.toString());
    } catch (RuntimeException ex) {
      log.warn(
          "onNutritionIntakeDiverged failed for trace={}: {}", event.traceId(), ex.toString(), ex);
    }
  }

  // ---- Trigger listener 3: preference-01a -----------------------------------------------------

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onPreferenceUpdated(HardConstraintsUpdatedEvent event) {
    try {
      UUID householdId = resolveHouseholdForUser(event.userId());
      if (householdId == null) {
        log.debug(
            "HardConstraintsUpdatedEvent for user {} maps to no household; nothing to re-opt",
            event.userId());
        return;
      }
      UUID triggerEventId = deriveTriggerEventId("preference", event.traceId(), event.scopeId());
      for (Plan plan : activePlans(householdId)) {
        boolean material = preferenceMaterialityFilter.isMaterial(event, plan);
        Optional<UUID> suggestionId =
            material
                ? requestReopt(plan, ReoptTriggerKind.PREFERENCE, triggerEventId, event.traceId())
                : Optional.empty();
        log.info(
            "onPreferenceUpdated plan={} fieldsChanged={} material={} suggestionId={}",
            plan.getId(),
            event.fieldsChanged(),
            material,
            suggestionId.orElse(null));
      }
    } catch (AiUnavailableException ex) {
      log.info(
          "onPreferenceUpdated: AI unavailable during downstream re-opt (graceful-degrade): {}",
          ex.toString());
    } catch (RuntimeException ex) {
      log.warn("onPreferenceUpdated failed for trace={}: {}", event.traceId(), ex.toString(), ex);
    }
  }

  // ---- Trigger listener 4: household-01b ------------------------------------------------------

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onHouseholdConfigChanged(
      com.example.mealprep.household.event.HouseholdSettingsChangedEvent event) {
    try {
      UUID householdId = event.householdId();
      UUID triggerEventId = deriveTriggerEventId("household", event.traceId(), event.scopeId());
      for (Plan plan : activePlans(householdId)) {
        boolean material = householdMaterialityFilter.isMaterial(event, plan);
        Optional<UUID> suggestionId =
            material
                ? requestReopt(
                    plan, ReoptTriggerKind.HOUSEHOLD_SETTINGS, triggerEventId, event.traceId())
                : Optional.empty();
        log.info(
            "onHouseholdConfigChanged plan={} changedPaths={} material={} suggestionId={}",
            plan.getId(),
            event.changedFieldPaths(),
            material,
            suggestionId.orElse(null));
      }
    } catch (AiUnavailableException ex) {
      log.info(
          "onHouseholdConfigChanged: AI unavailable during downstream re-opt"
              + " (graceful-degrade): {}",
          ex.toString());
    } catch (RuntimeException ex) {
      log.warn(
          "onHouseholdConfigChanged failed for trace={}: {}", event.traceId(), ex.toString(), ex);
    }
  }

  // ---- helpers --------------------------------------------------------------------------------

  /**
   * Calls the coordinator. {@link MidWeekReoptCoordinator#requestReopt} is
   * {@code @Transactional(REQUIRED)} so it joins this listener's {@code REQUIRES_NEW} tx; the
   * {@code ReoptSuggestedEvent} it publishes therefore has an active tx and is delivered to {@code
   * AFTER_COMMIT} subscribers. Idempotent on {@code (planId, triggerEventId)}.
   */
  private Optional<UUID> requestReopt(
      Plan plan, ReoptTriggerKind trigger, UUID triggerEventId, UUID upstreamTraceId) {
    UUID traceId = upstreamTraceId != null ? upstreamTraceId : UUID.randomUUID();
    return reoptCoordinator.requestReopt(plan.getId(), trigger, triggerEventId, traceId);
  }

  /**
   * Active plans for the household with the lazy day &rarr; slot &rarr; scheduled-recipe graph
   * forced while the listener's tx is open (the repository deliberately has no {@code @EntityGraph}
   * — Hibernate-6 multi-bag trap). The materiality filters traverse this graph.
   */
  private List<Plan> activePlans(UUID householdId) {
    List<Plan> plans = planRepository.findByHouseholdIdAndStatusIn(householdId, ACTIVE_STATUSES);
    for (Plan plan : plans) {
      for (Day day : plan.getDays()) {
        for (MealSlot slot : day.getSlots()) {
          ScheduledRecipe sr = slot.getScheduledRecipe();
          if (sr != null) {
            sr.getRecipeId(); // touch to force the lazy proxy inside the open session
          }
        }
      }
    }
    return plans;
  }

  /** Resolve a user's (single, v1-invariant) household id, or null if they're in none. */
  private UUID resolveHouseholdForUser(UUID userId) {
    if (userId == null) {
      return null;
    }
    return householdQueryService.getByUserId(userId).map(HouseholdDto::id).orElse(null);
  }

  private static boolean weekContains(Plan plan, java.time.LocalDate onDate) {
    if (onDate == null) {
      return false;
    }
    java.time.LocalDate weekStart = plan.getWeekStartDate();
    return !onDate.isBefore(weekStart) && onDate.isBefore(weekStart.plusDays(7));
  }

  /**
   * Derive a stable {@code triggerEventId} from the event's identity. The upstream events carry no
   * explicit event-id; a deterministic UUID over {@code (type-tag, traceId, scopeId)} means a
   * re-fire of the SAME logical event coalesces in the coordinator's idempotency check, while two
   * distinct events (different trace) produce distinct ids.
   */
  private static UUID deriveTriggerEventId(String typeTag, UUID traceId, UUID scopeId) {
    String seed =
        typeTag + '|' + (traceId == null ? "" : traceId) + '|' + (scopeId == null ? "" : scopeId);
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
  }
}
