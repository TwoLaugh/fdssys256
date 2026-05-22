package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NotificationPayloadJsonTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  static Stream<NotificationPayload> payloads() {
    return Stream.of(
        new NotificationPayload.ItemNearExpiryPayload(
            NotificationKind.PROVISION_ITEM_NEAR_EXPIRY,
            List.of(UUID.randomUUID()),
            LocalDate.now(),
            1),
        new NotificationPayload.ItemSpoiledPayload(
            NotificationKind.PROVISION_ITEM_SPOILED, List.of(UUID.randomUUID()), List.of("egg")),
        new NotificationPayload.DefrostReminderPayload(
            NotificationKind.PROVISION_DEFROST_REMINDER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.now()),
        new NotificationPayload.NutritionDivergedPayload(
            NotificationKind.NUTRITION_INTAKE_DIVERGED,
            LocalDate.now(),
            "protein",
            new BigDecimal("100"),
            new BigDecimal("80"),
            new BigDecimal("0.20")),
        new NotificationPayload.HealthDirectivePayload(
            NotificationKind.HEALTH_DIRECTIVE_RECEIVED, UUID.randomUUID(), "summary"),
        new NotificationPayload.PrepReminderPayload(
            NotificationKind.PLANNER_PREP_REMINDER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "marinate",
            Instant.now()),
        new NotificationPayload.ReoptSuggestedPayload(
            NotificationKind.PLANNER_REOPT_SUGGESTED,
            UUID.randomUUID(),
            "trigger",
            List.of(UUID.randomUUID())),
        new NotificationPayload.PlanGeneratedPayload(
            NotificationKind.PLANNER_PLAN_GENERATED, UUID.randomUUID(), 2));
  }

  @ParameterizedTest
  @MethodSource("payloads")
  void roundTrip_preservesConcreteType(NotificationPayload payload) throws Exception {
    String json = objectMapper.writeValueAsString(payload);
    NotificationPayload back = objectMapper.readValue(json, NotificationPayload.class);

    assertThat(back).isEqualTo(payload);
    assertThat(back.getClass()).isEqualTo(payload.getClass());
    assertThat(back.kind()).isEqualTo(payload.kind());
  }
}
