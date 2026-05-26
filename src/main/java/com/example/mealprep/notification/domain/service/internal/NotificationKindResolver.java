package com.example.mealprep.notification.domain.service.internal;

import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.notification.event.StapleReplenishmentNeededEvent;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PrepReminderEvent;
import com.example.mealprep.planner.event.ReoptSuggestedEvent;
import com.example.mealprep.provisions.event.DefrostReminderEvent;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Maps each producer event to a {@link NotificationDraft} — the single source of truth for the
 * event-to-notification table in {@code lld/notification.md} §Consumed. Severity, payload, action
 * URI, householded routing and bundling key are all decided here so the dispatcher stays
 * event-agnostic.
 *
 * <p>Household routing (v1): when an event is household-scoped, the target user is the household's
 * primary member (per §Household routing); {@code householdId} is still stored on the row so a
 * future "all members" mode is a query change.
 */
@Component
public class NotificationKindResolver {

  /** Severity threshold for {@code NUTRITION_INTAKE_DIVERGED}: >= this is ATTENTION, else INFO. */
  static final BigDecimal DIVERGENCE_ATTENTION_THRESHOLD = new BigDecimal("0.40");

  private final HouseholdQueryService householdQueryService;

  public NotificationKindResolver(HouseholdQueryService householdQueryService) {
    this.householdQueryService = householdQueryService;
  }

  public NotificationDraft resolve(ItemNearingExpiryEvent event) {
    UUID targetUser = resolveTargetUser(event.userId(), event.householdId());
    List<UUID> itemIds = event.inventoryItemIds() == null ? List.of() : event.inventoryItemIds();
    NotificationKind kind = NotificationKind.PROVISION_ITEM_NEAR_EXPIRY;
    var payload =
        new NotificationPayload.ItemNearExpiryPayload(
            kind, itemIds, event.earliestExpiry(), itemIds.size());
    return new NotificationDraft(
        targetUser,
        event.householdId(),
        kind,
        NotificationSeverity.ATTENTION,
        "Items nearing expiry",
        itemIds.size() + " item(s) are nearing expiry.",
        payload,
        "/app/provisions/inventory",
        event.traceId(),
        event.traceId(),
        null,
        Origin.SYSTEM_SCHEDULED,
        null,
        kind.name());
  }

  public NotificationDraft resolve(ItemSpoiledEvent event) {
    NotificationKind kind = NotificationKind.PROVISION_ITEM_SPOILED;
    List<UUID> itemIds = event.affectedItemIds() == null ? List.of() : event.affectedItemIds();
    var payload = new NotificationPayload.ItemSpoiledPayload(kind, itemIds, List.of());
    return new NotificationDraft(
        event.userId(),
        null,
        kind,
        NotificationSeverity.ATTENTION,
        "Items spoiled",
        itemIds.size() + " item(s) have spoiled.",
        payload,
        "/app/provisions/inventory",
        event.traceId(),
        event.traceId(),
        null,
        Origin.USER,
        null,
        kind.name());
  }

  public NotificationDraft resolve(DefrostReminderEvent event) {
    UUID targetUser = resolveTargetUser(event.userId(), event.householdId());
    NotificationKind kind = NotificationKind.PROVISION_DEFROST_REMINDER;
    var payload =
        new NotificationPayload.DefrostReminderPayload(
            kind, event.inventoryItemId(), event.mealSlotId(), event.defrostBy());
    return new NotificationDraft(
        targetUser,
        event.householdId(),
        kind,
        NotificationSeverity.ATTENTION,
        "Defrost reminder",
        "An item needs defrosting for an upcoming meal.",
        payload,
        "/app/provisions/inventory",
        event.traceId(),
        event.traceId(),
        keyOf(event.mealSlotId()),
        Origin.SYSTEM_SCHEDULED,
        null,
        kind.name());
  }

  public NotificationDraft resolve(NutritionIntakeDivergedEvent event) {
    NotificationKind kind = NotificationKind.NUTRITION_INTAKE_DIVERGED;
    String nutrientKey =
        event.divergedMacros() == null || event.divergedMacros().isEmpty()
            ? null
            : event.divergedMacros().iterator().next();
    BigDecimal divergencePct = worstAbsoluteVariance(event, nutrientKey);
    NotificationSeverity severity =
        divergencePct != null && divergencePct.compareTo(DIVERGENCE_ATTENTION_THRESHOLD) >= 0
            ? NotificationSeverity.ATTENTION
            : NotificationSeverity.INFO;
    BigDecimal targetValue = macroValue(event, nutrientKey, true);
    BigDecimal actualValue = macroValue(event, nutrientKey, false);
    var payload =
        new NotificationPayload.NutritionDivergedPayload(
            kind, event.onDate(), nutrientKey, targetValue, actualValue, divergencePct);
    return new NotificationDraft(
        event.userId(),
        null,
        kind,
        severity,
        "Nutrition target diverged",
        "Your intake diverged from your target.",
        payload,
        "/app/nutrition/intake/" + event.onDate(),
        event.traceId(),
        event.traceId(),
        keyOf(event.onDate()),
        Origin.SYSTEM_SCHEDULED,
        null,
        kind.name());
  }

  public NotificationDraft resolve(HealthDirectiveReceivedEvent event) {
    NotificationKind kind = NotificationKind.HEALTH_DIRECTIVE_RECEIVED;
    var payload =
        new NotificationPayload.HealthDirectivePayload(
            kind, event.directiveId(), "A health directive was received.");
    return new NotificationDraft(
        event.userId(),
        null,
        kind,
        NotificationSeverity.URGENT,
        "Health directive received",
        "A new health directive needs your review.",
        payload,
        "/app/nutrition/health-directives/" + event.directiveId(),
        event.traceId(),
        event.traceId(),
        keyOf(event.directiveId()),
        Origin.AI_FEEDBACK,
        null,
        kind.name());
  }

  public NotificationDraft resolve(PrepReminderEvent event) {
    NotificationKind kind = NotificationKind.PLANNER_PREP_REMINDER;
    var payload =
        new NotificationPayload.PrepReminderPayload(
            kind, event.plannedMealSlotId(), event.recipeId(), event.prepStep(), event.prepBy());
    return new NotificationDraft(
        event.userId(),
        null,
        kind,
        NotificationSeverity.ATTENTION,
        "Prep reminder",
        "An upcoming meal needs advance prep.",
        payload,
        "/app/planner/slots/" + event.plannedMealSlotId(),
        event.traceId(),
        event.traceId(),
        keyOf(event.plannedMealSlotId()),
        Origin.SYSTEM_SCHEDULED,
        null,
        kind.name());
  }

  public NotificationDraft resolve(ReoptSuggestedEvent event) {
    UUID targetUser = resolveTargetUser(null, event.householdId());
    NotificationKind kind = NotificationKind.PLANNER_REOPT_SUGGESTED;
    List<UUID> affected = event.affectedSlotIds() == null ? List.of() : event.affectedSlotIds();
    var payload =
        new NotificationPayload.ReoptSuggestedPayload(
            kind, event.planId(), event.summary(), affected);
    return new NotificationDraft(
        targetUser,
        event.householdId(),
        kind,
        NotificationSeverity.ATTENTION,
        "Re-optimisation suggested",
        "A re-optimisation is suggested for your plan.",
        payload,
        "/app/plans/" + event.planId(),
        event.traceId(),
        event.traceId(),
        keyOf(event.planId()),
        Origin.SYSTEM_REOPT,
        null,
        kind.name());
  }

  public NotificationDraft resolve(PlanGeneratedEvent event) {
    UUID targetUser = resolveTargetUser(null, event.householdId());
    NotificationKind kind = NotificationKind.PLANNER_PLAN_GENERATED;
    var payload =
        new NotificationPayload.PlanGeneratedPayload(kind, event.planId(), event.generation());
    return new NotificationDraft(
        targetUser,
        event.householdId(),
        kind,
        NotificationSeverity.INFO,
        "Plan generated",
        "Your plan is ready.",
        payload,
        "/app/plans/" + event.planId(),
        event.traceId(),
        event.traceId(),
        keyOf(event.planId()),
        Origin.SYSTEM_REOPT,
        null,
        kind.name());
  }

  public NotificationDraft resolve(StapleReplenishmentNeededEvent event) {
    NotificationKind kind = NotificationKind.STAPLE_REPLENISHMENT_NEEDED;
    List<UUID> itemIds = event.inventoryItemIds() == null ? List.of() : event.inventoryItemIds();
    List<String> mappingKeys =
        event.ingredientMappingKeys() == null ? List.of() : event.ingredientMappingKeys();
    var payload =
        new NotificationPayload.StapleReplenishmentPayload(
            kind, itemIds, mappingKeys, event.lowestStockRatio());
    return new NotificationDraft(
        event.userId(),
        null,
        kind,
        NotificationSeverity.INFO,
        "Staples running low",
        itemIds.size() + " staple item(s) need replenishing.",
        payload,
        "/app/provisions/inventory",
        event.traceId(),
        event.traceId(),
        null,
        Origin.SYSTEM_SCHEDULED,
        null,
        kind.name());
  }

  /**
   * NOTIF-16 feedback-confirmation. Positive-outcome only: by the time this resolver is reached the
   * {@code FeedbackEventListener} has already applied the fire-condition gate (≥1 destination
   * applied), so we map unconditionally here. The payload's "applied" list is built from {@code
   * appliedDestinations()} — the subset that actually succeeded — NOT {@code destinationsTouched()}
   * (all attempted), so a partial success lists only the destinations that took. Non-householded —
   * the originating feedback is the submitting user's own (the event's {@code userId} is the
   * author), so we dispatch directly to them with no household fan-out. Severity INFO (a positive
   * confirmation, not an alert). {@code bundlingKey = feedbackId} delivers the NOTIF-16 "no storm"
   * guarantee: a re-delivered event for the same entry collapses onto the one row.
   */
  public NotificationDraft resolve(FeedbackProcessedEvent event) {
    NotificationKind kind = NotificationKind.FEEDBACK_CONFIRMATION;
    List<String> applied =
        event.appliedDestinations() == null
            ? List.of()
            : event.appliedDestinations().stream().sorted().map(Destination::name).toList();
    var payload =
        new NotificationPayload.FeedbackConfirmationPayload(kind, event.feedbackId(), applied);
    String body =
        applied.isEmpty()
            ? "Your feedback was applied."
            : "Your feedback updated " + String.join(", ", applied) + ".";
    return new NotificationDraft(
        event.userId(),
        null,
        kind,
        NotificationSeverity.INFO,
        "Feedback applied",
        body,
        payload,
        "/app/feedback/" + event.feedbackId(),
        event.feedbackId(),
        event.traceId(),
        event.feedbackId().toString(),
        Origin.USER,
        null,
        kind.name());
  }

  // ---------------- helpers ----------------

  /**
   * Resolve the dispatch target. When {@code householdId} is set, route to the household's primary
   * member (v1 primary-user-only routing); otherwise dispatch to {@code eventUserId}. Falls back to
   * {@code eventUserId} when the household has no resolvable primary.
   */
  private UUID resolveTargetUser(UUID eventUserId, UUID householdId) {
    if (householdId == null) {
      return eventUserId;
    }
    Optional<UUID> primary =
        householdQueryService.getById(householdId).flatMap(NotificationKindResolver::primaryUserId);
    return primary.orElse(eventUserId);
  }

  private static Optional<UUID> primaryUserId(HouseholdDto household) {
    if (household.members() == null) {
      return Optional.empty();
    }
    return household.members().stream()
        .filter(member -> member.role() == HouseholdRole.primary)
        .map(HouseholdMemberDto::userId)
        .findFirst();
  }

  private static String keyOf(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * The largest absolute percentage variance across the diverged macros (the summary's {@code
   * percentVariance} is fractional — 0.20 means +20%). When a specific {@code nutrientKey} is in
   * scope, that macro's variance is preferred; otherwise the worst across the map is used. Null
   * when the summary is absent (→ INFO).
   */
  private static BigDecimal worstAbsoluteVariance(
      NutritionIntakeDivergedEvent event, String nutrientKey) {
    if (event.summary() == null || event.summary().percentVariance() == null) {
      return null;
    }
    var variance = event.summary().percentVariance();
    if (nutrientKey != null && variance.get(nutrientKey) != null) {
      return variance.get(nutrientKey).abs().setScale(2, RoundingMode.HALF_UP);
    }
    return variance.values().stream()
        .filter(java.util.Objects::nonNull)
        .map(BigDecimal::abs)
        .max(BigDecimal::compareTo)
        .map(value -> value.setScale(2, RoundingMode.HALF_UP))
        .orElse(null);
  }

  private static BigDecimal macroValue(
      NutritionIntakeDivergedEvent event, String nutrientKey, boolean planned) {
    if (event.summary() == null || nutrientKey == null) {
      return null;
    }
    var map = planned ? event.summary().plannedSoFar() : event.summary().actualSoFar();
    return map == null ? null : map.get(nutrientKey);
  }
}
