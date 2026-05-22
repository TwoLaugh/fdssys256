package com.example.mealprep.notification.domain.entity;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Kind-specific payload carried by a {@link Notification}, persisted as JSONB. Sealed interface
 * with one record permit per {@link NotificationKind}. Jackson polymorphic (de)serialisation keys
 * on the {@code kind} discriminator (each record's first component is its own {@link
 * NotificationKind}), so a stored payload round-trips back to the correct concrete record per
 * {@code lld/notification.md} §Notification Kinds.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(
      value = NotificationPayload.ItemNearExpiryPayload.class,
      name = "PROVISION_ITEM_NEAR_EXPIRY"),
  @JsonSubTypes.Type(
      value = NotificationPayload.ItemSpoiledPayload.class,
      name = "PROVISION_ITEM_SPOILED"),
  @JsonSubTypes.Type(
      value = NotificationPayload.DefrostReminderPayload.class,
      name = "PROVISION_DEFROST_REMINDER"),
  @JsonSubTypes.Type(
      value = NotificationPayload.NutritionDivergedPayload.class,
      name = "NUTRITION_INTAKE_DIVERGED"),
  @JsonSubTypes.Type(
      value = NotificationPayload.HealthDirectivePayload.class,
      name = "HEALTH_DIRECTIVE_RECEIVED"),
  @JsonSubTypes.Type(
      value = NotificationPayload.PrepReminderPayload.class,
      name = "PLANNER_PREP_REMINDER"),
  @JsonSubTypes.Type(
      value = NotificationPayload.ReoptSuggestedPayload.class,
      name = "PLANNER_REOPT_SUGGESTED"),
  @JsonSubTypes.Type(
      value = NotificationPayload.PlanGeneratedPayload.class,
      name = "PLANNER_PLAN_GENERATED")
})
public sealed interface NotificationPayload
    permits NotificationPayload.ItemNearExpiryPayload,
        NotificationPayload.ItemSpoiledPayload,
        NotificationPayload.DefrostReminderPayload,
        NotificationPayload.NutritionDivergedPayload,
        NotificationPayload.HealthDirectivePayload,
        NotificationPayload.PrepReminderPayload,
        NotificationPayload.ReoptSuggestedPayload,
        NotificationPayload.PlanGeneratedPayload {

  /** Discriminator — also the notification kind. Always non-null. */
  NotificationKind kind();

  record ItemNearExpiryPayload(
      NotificationKind kind, List<UUID> inventoryItemIds, LocalDate earliestExpiry, int itemCount)
      implements NotificationPayload {}

  record ItemSpoiledPayload(
      NotificationKind kind, List<UUID> inventoryItemIds, List<String> ingredientMappingKeys)
      implements NotificationPayload {}

  record DefrostReminderPayload(
      NotificationKind kind, UUID inventoryItemId, UUID plannedMealSlotId, Instant defrostBy)
      implements NotificationPayload {}

  record NutritionDivergedPayload(
      NotificationKind kind,
      LocalDate date,
      String nutrientKey,
      BigDecimal targetValue,
      BigDecimal actualValue,
      BigDecimal divergencePct)
      implements NotificationPayload {}

  record HealthDirectivePayload(NotificationKind kind, UUID directiveId, String summary)
      implements NotificationPayload {}

  record PrepReminderPayload(
      NotificationKind kind, UUID plannedMealSlotId, UUID recipeId, String prepStep, Instant prepBy)
      implements NotificationPayload {}

  record ReoptSuggestedPayload(
      NotificationKind kind, UUID planId, String triggerSummary, List<UUID> affectedSlotIds)
      implements NotificationPayload {}

  record PlanGeneratedPayload(NotificationKind kind, UUID planId, int generation)
      implements NotificationPayload {}
}
