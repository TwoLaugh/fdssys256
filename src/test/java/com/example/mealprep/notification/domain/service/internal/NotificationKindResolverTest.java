package com.example.mealprep.notification.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.example.mealprep.notification.domain.entity.NotificationSeverity;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.DivergenceSummaryDto;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.event.PlanGeneratedEvent;
import com.example.mealprep.planner.event.PrepReminderEvent;
import com.example.mealprep.planner.event.ReoptSuggestedEvent;
import com.example.mealprep.provisions.event.DefrostReminderEvent;
import com.example.mealprep.provisions.event.ItemNearingExpiryEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationKindResolverTest {

  @Mock private HouseholdQueryService householdQueryService;

  private NotificationKindResolver resolver() {
    return new NotificationKindResolver(householdQueryService);
  }

  @Test
  void resolve_itemNearingExpiry_attentionWithItemCount() {
    UUID user = UUID.randomUUID();
    var event =
        new ItemNearingExpiryEvent(
            user,
            null,
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            LocalDate.now(),
            UUID.randomUUID(),
            Instant.now());

    NotificationDraft draft = resolver().resolve(event).orElseThrow();

    assertThat(draft.kind()).isEqualTo(NotificationKind.PROVISION_ITEM_NEAR_EXPIRY);
    assertThat(draft.severity()).isEqualTo(NotificationSeverity.ATTENTION);
    assertThat(draft.userId()).isEqualTo(user);
    // Deep links are IA routes (notifications page spec §8 Q2) — pantry owns provisions alerts.
    assertThat(draft.actionTargetUri()).isEqualTo("/pantry");
  }

  @Test
  void resolve_itemSpoiled_attention() {
    UUID user = UUID.randomUUID();
    var event =
        new ItemSpoiledEvent(
            user, List.of(UUID.randomUUID()), "expired", UUID.randomUUID(), Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    assertThat(draft.kind()).isEqualTo(NotificationKind.PROVISION_ITEM_SPOILED);
    assertThat(draft.severity()).isEqualTo(NotificationSeverity.ATTENTION);
    assertThat(draft.actionTargetUri()).isEqualTo("/pantry");
  }

  @Test
  void resolve_defrostReminder_bundlesPerMealSlot() {
    UUID user = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    var event =
        new DefrostReminderEvent(
            user, null, UUID.randomUUID(), slot, Instant.now(), UUID.randomUUID(), Instant.now());

    NotificationDraft draft = resolver().resolve(event).orElseThrow();

    assertThat(draft.kind()).isEqualTo(NotificationKind.PROVISION_DEFROST_REMINDER);
    assertThat(draft.bundlingKey()).isEqualTo(slot.toString());
    assertThat(draft.actionTargetUri()).isEqualTo("/pantry");
  }

  @Test
  void resolve_nutritionDiverged_lowVariance_isInfo() {
    UUID user = UUID.randomUUID();
    var summary =
        new DivergenceSummaryDto(
            Map.of("protein", new BigDecimal("100")),
            Map.of("protein", new BigDecimal("90")),
            Map.of("protein", new BigDecimal("0.10")));
    var event =
        new NutritionIntakeDivergedEvent(
            user, LocalDate.now(), Set.of("protein"), summary, UUID.randomUUID(), Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    assertThat(draft.severity()).isEqualTo(NotificationSeverity.INFO);
    // IA route, no date suffix — the onDate context rides the typed payload.
    assertThat(draft.actionTargetUri()).isEqualTo("/nutrition");
  }

  @Test
  void resolve_nutritionDiverged_highVariance_isAttention() {
    UUID user = UUID.randomUUID();
    var summary =
        new DivergenceSummaryDto(
            Map.of("protein", new BigDecimal("100")),
            Map.of("protein", new BigDecimal("150")),
            Map.of("protein", new BigDecimal("0.50")));
    var event =
        new NutritionIntakeDivergedEvent(
            user, LocalDate.now(), Set.of("protein"), summary, UUID.randomUUID(), Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    assertThat(draft.severity()).isEqualTo(NotificationSeverity.ATTENTION);
  }

  @Test
  void resolve_healthDirective_isUrgent() {
    UUID user = UUID.randomUUID();
    var event =
        new HealthDirectiveReceivedEvent(
            user,
            UUID.randomUUID(),
            DirectiveType.values()[0],
            "platform",
            Instant.now(),
            UUID.randomUUID(),
            Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    assertThat(draft.kind()).isEqualTo(NotificationKind.HEALTH_DIRECTIVE_RECEIVED);
    assertThat(draft.severity()).isEqualTo(NotificationSeverity.URGENT);
    assertThat(draft.actionTargetUri()).isEqualTo("/nutrition");
  }

  @Test
  void resolve_prepReminder_attention() {
    UUID user = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    var event =
        new PrepReminderEvent(
            user,
            slot,
            UUID.randomUUID(),
            "marinate",
            Instant.now(),
            UUID.randomUUID(),
            Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    assertThat(draft.kind()).isEqualTo(NotificationKind.PLANNER_PREP_REMINDER);
    assertThat(draft.bundlingKey()).isEqualTo(slot.toString());
    // IA route — the slot id rides the typed payload, not the URI.
    assertThat(draft.actionTargetUri()).isEqualTo("/plan");
  }

  @Test
  void resolve_reoptSuggested_resolvesPrimaryUserFromHousehold() {
    UUID household = UUID.randomUUID();
    UUID primaryUser = UUID.randomUUID();
    stubPrimary(household, primaryUser);
    var event =
        new ReoptSuggestedEvent(
            UUID.randomUUID(),
            household,
            LocalDate.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "summary",
            UUID.randomUUID(),
            Instant.now());

    NotificationDraft draft = resolver().resolve(event).orElseThrow();

    assertThat(draft.kind()).isEqualTo(NotificationKind.PLANNER_REOPT_SUGGESTED);
    assertThat(draft.userId()).isEqualTo(primaryUser);
    assertThat(draft.householdId()).isEqualTo(household);
    assertThat(draft.actionTargetUri()).isEqualTo("/plan");
  }

  @Test
  void resolve_planGenerated_isInfo_andHouseholded() {
    UUID household = UUID.randomUUID();
    UUID primaryUser = UUID.randomUUID();
    stubPrimary(household, primaryUser);
    var event =
        new PlanGeneratedEvent(
            UUID.randomUUID(),
            household,
            LocalDate.now(),
            1,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            false,
            false,
            UUID.randomUUID(),
            Instant.now());

    NotificationDraft draft = resolver().resolve(event).orElseThrow();

    assertThat(draft.kind()).isEqualTo(NotificationKind.PLANNER_PLAN_GENERATED);
    assertThat(draft.severity()).isEqualTo(NotificationSeverity.INFO);
    assertThat(draft.userId()).isEqualTo(primaryUser);
    assertThat(draft.actionTargetUri()).isEqualTo("/plan");
  }

  @Test
  void resolve_planGenerated_unknownHousehold_resolvesNobody() {
    // Pins the CI failure class: a PlanGeneratedEvent whose household cannot be resolved must
    // dispatch nothing, not insert a null-user preference row.
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household)).thenReturn(Optional.empty());
    var event =
        new PlanGeneratedEvent(
            UUID.randomUUID(),
            household,
            LocalDate.now(),
            1,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            false,
            false,
            UUID.randomUUID(),
            Instant.now());

    assertThat(resolver().resolve(event)).isEmpty();
  }

  @Test
  void resolve_planGenerated_householdWithoutPrimary_resolvesNobody() {
    UUID household = UUID.randomUUID();
    HouseholdMemberDto plainMember =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            household,
            UUID.randomUUID(),
            HouseholdRole.member,
            "Member",
            null,
            1,
            Instant.now(),
            0L);
    HouseholdDto dto =
        new HouseholdDto(household, "Home", null, List.of(plainMember), Instant.now(), 0L);
    when(householdQueryService.getById(household)).thenReturn(Optional.of(dto));
    var event =
        new PlanGeneratedEvent(
            UUID.randomUUID(),
            household,
            LocalDate.now(),
            1,
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            false,
            false,
            false,
            UUID.randomUUID(),
            Instant.now());

    assertThat(resolver().resolve(event)).isEmpty();
  }

  @Test
  void resolve_reoptSuggested_unknownHousehold_resolvesNobody() {
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(household)).thenReturn(Optional.empty());
    var event =
        new ReoptSuggestedEvent(
            UUID.randomUUID(),
            household,
            LocalDate.now(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "summary",
            UUID.randomUUID(),
            Instant.now());

    assertThat(resolver().resolve(event)).isEmpty();
  }

  @Test
  void draft_rejectsNullUser() {
    // The record is the last line of defence: no code path may build a userless draft.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new NotificationDraft(
                    null,
                    null,
                    NotificationKind.PLANNER_PLAN_GENERATED,
                    NotificationSeverity.INFO,
                    "t",
                    "b",
                    null,
                    "/plan",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "tag"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void resolve_feedbackProcessed_isInfo_directToAuthor_bundlesPerFeedbackId() {
    UUID user = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    var event =
        new FeedbackProcessedEvent(
            feedbackId,
            user,
            Set.of(Destination.PROVISIONS),
            Set.of(Destination.PROVISIONS),
            false,
            false,
            traceId,
            Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    assertThat(draft.kind()).isEqualTo(NotificationKind.FEEDBACK_CONFIRMATION);
    assertThat(draft.severity()).isEqualTo(NotificationSeverity.INFO);
    // Non-householded — dispatched directly to the submitting user (the event author).
    assertThat(draft.userId()).isEqualTo(user);
    assertThat(draft.householdId()).isNull();
    // NOTIF-16 "no storm" guarantee: bundlingKey is the feedbackId.
    assertThat(draft.bundlingKey()).isEqualTo(feedbackId.toString());
    assertThat(draft.sourceEventId()).isEqualTo(feedbackId);
    assertThat(draft.traceId()).isEqualTo(traceId);
    // IA route (notifications page spec §8 Q2) — feedback history lives on /activity; the
    // feedbackId rides the typed payload.
    assertThat(draft.actionTargetUri()).isEqualTo("/activity");
    assertThat(draft.metricTag()).isEqualTo(NotificationKind.FEEDBACK_CONFIRMATION.name());
    assertThat(draft.payload()).isInstanceOf(NotificationPayload.FeedbackConfirmationPayload.class);
    var payload = (NotificationPayload.FeedbackConfirmationPayload) draft.payload();
    assertThat(payload.feedbackId()).isEqualTo(feedbackId);
    assertThat(payload.appliedDestinations()).containsExactly("PROVISIONS");
  }

  @Test
  void resolve_feedbackProcessed_partialSuccess_listsOnlyTheAppliedDestinations() {
    UUID user = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    // Partial success: PROVISIONS and NUTRITION were both attempted (touched) but only PROVISIONS
    // applied. partialFailure=true; something applied, so the resolver still maps (the listener
    // gate has already decided to fire). The payload must list ONLY the succeeded destination.
    var event =
        new FeedbackProcessedEvent(
            feedbackId,
            user,
            Set.of(Destination.PROVISIONS, Destination.NUTRITION), // attempted
            Set.of(Destination.PROVISIONS), // succeeded
            true,
            false,
            UUID.randomUUID(),
            Instant.now());

    NotificationDraft draft = resolver().resolve(event);

    var payload = (NotificationPayload.FeedbackConfirmationPayload) draft.payload();
    // Only the applied (succeeded) destination, NOT the failed NUTRITION attempt.
    assertThat(payload.appliedDestinations()).containsExactly("PROVISIONS");
  }

  private void stubPrimary(UUID household, UUID primaryUser) {
    HouseholdMemberDto primary =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            household,
            primaryUser,
            HouseholdRole.primary,
            "Primary",
            null, // username (auth join; not exercised here)
            0,
            Instant.now(),
            0L);
    HouseholdMemberDto other =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            household,
            UUID.randomUUID(),
            HouseholdRole.member,
            "Member",
            null, // username (auth join; not exercised here)
            1,
            Instant.now(),
            0L);
    HouseholdDto dto =
        new HouseholdDto(
            household, "Home", primaryUser, List.of(other, primary), Instant.now(), 0L);
    when(householdQueryService.getById(household)).thenReturn(Optional.of(dto));
  }
}
