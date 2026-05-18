package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.InvalidDirectiveRoutingException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.example.mealprep.nutrition.spi.DirectiveApplyTarget;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link DirectiveApplier}. Cross-module collaborators (repositories, event
 * publisher, SPI {@link ObjectProvider}) are Mockito mocks; {@link NutritionTargets} / {@link
 * HealthDirective} are real instances and the {@link ObjectMapper} + {@link Clock} are real. Covers
 * route dispatch, every adjust_target tier, the no-op (unchanged value / null delta / null tier)
 * paths, audit-row content, and the published event.
 */
@ExtendWith(MockitoExtension.class)
class DirectiveApplierTest {

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;

  @SuppressWarnings("unchecked")
  private final ObjectProvider<DirectiveApplyTarget> preferenceProvider =
      org.mockito.Mockito.mock(ObjectProvider.class);

  @Mock private DirectiveApplyTarget preferenceTarget;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Instant fixedNow = Instant.parse("2026-05-18T10:15:30Z");
  private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

  private DirectiveApplier applier;
  private UUID userId;
  private UUID directiveId;
  private UUID actorUserId;

  @BeforeEach
  void setUp() {
    applier =
        new DirectiveApplier(
            targetsRepository,
            auditRepository,
            preferenceProvider,
            eventPublisher,
            objectMapper,
            clock);
    userId = UUID.randomUUID();
    directiveId = UUID.randomUUID();
    actorUserId = UUID.randomUUID();
  }

  private HealthDirective directive(String mapsToModel, String tier) {
    return HealthDirective.builder()
        .id(directiveId)
        .userId(userId)
        .mapsToModel(mapsToModel)
        .mapsToTier(tier)
        .temporary(true)
        .autoExpiresAt(fixedNow.plusSeconds(3600))
        .build();
  }

  private NutritionTargets targetsWithProteinFloor(BigDecimal proteinFloor) {
    return NutritionTestData.targets().withUserId(userId).withProteinFloor(proteinFloor).build();
  }

  private DirectiveInstructionDocument adjust(BigDecimal proposedFloor) {
    Map<String, JsonNode> extras =
        proposedFloor == null
            ? Map.of()
            : Map.of("proposedFloor", objectMapper.valueToTree(proposedFloor));
    return new DirectiveInstructionDocument("adjust_target", "x", "global", null, extras);
  }

  // ---------------- routing ----------------

  @Test
  void null_route_throws_invalid_routing() {
    HealthDirective d = directive(null, "protein_floor_g");
    assertThatThrownBy(() -> applier.apply(d, adjust(BigDecimal.TEN), actorUserId))
        .isInstanceOf(InvalidDirectiveRoutingException.class);
    verifyNoInteractions(targetsRepository, auditRepository, eventPublisher);
  }

  @Test
  void unknown_route_throws_invalid_routing_carrying_the_bad_value() {
    HealthDirective d = directive("galaxy_model", "protein_floor_g");
    assertThatThrownBy(() -> applier.apply(d, adjust(BigDecimal.TEN), actorUserId))
        .isInstanceOf(InvalidDirectiveRoutingException.class)
        .extracting(e -> ((InvalidDirectiveRoutingException) e).mapsToModel())
        .isEqualTo("galaxy_model");
  }

  @Test
  void preference_route_delegates_to_spi_with_directive_fields() {
    when(preferenceProvider.getObject()).thenReturn(preferenceTarget);
    HealthDirective d = directive("preference_model", null);
    DirectiveInstructionDocument doc =
        new DirectiveInstructionDocument("restrict_ingredient", "peanut", "global", null, null);

    applier.apply(d, doc, actorUserId);

    verify(preferenceTarget)
        .applyPreferenceDirective(
            eq(userId),
            eq(doc),
            eq(true),
            eq(fixedNow.plusSeconds(3600)),
            eq(directiveId),
            eq(actorUserId));
    verifyNoInteractions(targetsRepository, auditRepository, eventPublisher);
  }

  // ---------------- nutrition_model: missing targets ----------------

  @Test
  void nutrition_route_without_targets_row_throws_not_found() {
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());
    HealthDirective d = directive("nutrition_model", "protein_floor_g");
    assertThatThrownBy(() -> applier.apply(d, adjust(BigDecimal.TEN), actorUserId))
        .isInstanceOf(NutritionTargetsNotFoundException.class);
  }

  // ---------------- nutrition_model: non-adjust action is audit-only no-op ----------------

  @Test
  void nutrition_route_non_adjust_action_makes_no_changes_no_save_no_event() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", "protein_floor_g");
    DirectiveInstructionDocument rebalance =
        new DirectiveInstructionDocument("rebalance_macros", null, "global", null, Map.of());

    applier.apply(d, rebalance, actorUserId);

    verify(targetsRepository, never()).saveAndFlush(any());
    verify(auditRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  // ---------------- nutrition_model: adjust_target tiers ----------------

  @Test
  void adjust_protein_floor_writes_audit_saves_and_publishes_event() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);
    HealthDirective d = directive("nutrition_model", "protein_floor_g");

    applier.apply(d, adjust(new BigDecimal("110.0")), actorUserId);

    assertThat(t.getProteinFloorG()).isEqualByComparingTo("110.0");

    ArgumentCaptor<NutritionTargetsAuditLog> auditCap =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(auditCap.capture());
    NutritionTargetsAuditLog audit = auditCap.getValue();
    assertThat(audit.getFieldPath()).isEqualTo("protein.floorG");
    assertThat(audit.getActorKind()).isEqualTo(ActorKind.HEALTH_DIRECTIVE);
    assertThat(audit.getActorUserId()).isEqualTo(actorUserId);
    assertThat(audit.getPreviousValueJson().decimalValue()).isEqualByComparingTo("90.0");
    assertThat(audit.getNewValueJson().decimalValue()).isEqualByComparingTo("110.0");
    assertThat(audit.getOccurredAt()).isEqualTo(fixedNow);

    verify(targetsRepository).saveAndFlush(t);
    ArgumentCaptor<NutritionTargetsChangedEvent> evtCap =
        ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
    verify(eventPublisher).publishEvent(evtCap.capture());
    NutritionTargetsChangedEvent evt = evtCap.getValue();
    assertThat(evt.userId()).isEqualTo(userId);
    assertThat(evt.targetsId()).isEqualTo(t.getId());
    assertThat(evt.changedFieldPaths()).containsExactly("protein.floorG");
    assertThat(evt.occurredAt()).isEqualTo(fixedNow);
  }

  @Test
  void adjust_carbs_fat_fibre_floors_each_set_the_right_field() {
    for (String[] tierField :
        new String[][] {
          {"carbs_floor_g", "carbs.floorG"},
          {"fat_floor_g", "fat.floorG"},
          {"fibre_floor_g", "fibre.floorG"}
        }) {
      NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
      when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
      when(targetsRepository.saveAndFlush(t)).thenReturn(t);
      HealthDirective d = directive("nutrition_model", tierField[0]);

      applier.apply(d, adjust(new BigDecimal("42.0")), actorUserId);

      ArgumentCaptor<NutritionTargetsChangedEvent> evtCap =
          ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
      verify(eventPublisher, times(1)).publishEvent(evtCap.capture());
      assertThat(evtCap.getValue().changedFieldPaths()).containsExactly(tierField[1]);
      switch (tierField[0]) {
        case "carbs_floor_g" -> assertThat(t.getCarbsFloorG()).isEqualByComparingTo("42.0");
        case "fat_floor_g" -> assertThat(t.getFatFloorG()).isEqualByComparingTo("42.0");
        default -> assertThat(t.getFibreFloorG()).isEqualByComparingTo("42.0");
      }
      org.mockito.Mockito.reset(eventPublisher, targetsRepository, auditRepository);
    }
  }

  @Test
  void adjust_daily_calorie_target_uses_int_exact_and_records_previous_as_decimal() {
    NutritionTargets t = targetsWithProteinFloor(null);
    // NutritionTestData default dailyCalorieTarget == 2000.
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);
    HealthDirective d = directive("nutrition_model", "daily_calorie_target");

    applier.apply(d, adjust(new BigDecimal("2300")), actorUserId);

    assertThat(t.getDailyCalorieTarget()).isEqualTo(2300);
    ArgumentCaptor<NutritionTargetsAuditLog> auditCap =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(auditCap.capture());
    assertThat(auditCap.getValue().getFieldPath()).isEqualTo("calories.dailyTarget");
    assertThat(auditCap.getValue().getPreviousValueJson().decimalValue())
        .isEqualByComparingTo("2000");
    assertThat(auditCap.getValue().getNewValueJson().decimalValue()).isEqualByComparingTo("2300");
  }

  @Test
  void calorie_target_alias_routes_same_as_daily_calorie_target() {
    NutritionTargets t = targetsWithProteinFloor(null);
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);
    HealthDirective d = directive("nutrition_model", "calorie_target");

    applier.apply(d, adjust(new BigDecimal("1800")), actorUserId);

    assertThat(t.getDailyCalorieTarget()).isEqualTo(1800);
    verify(eventPublisher).publishEvent(any(NutritionTargetsChangedEvent.class));
  }

  @Test
  void unchanged_floor_value_is_a_no_op_no_audit_no_save_no_event() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", "protein_floor_g");

    // proposed equals current (compareTo == 0 even with different scale)
    applier.apply(d, adjust(new BigDecimal("90.00")), actorUserId);

    verify(auditRepository, never()).save(any());
    verify(targetsRepository, never()).saveAndFlush(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void unchanged_calorie_value_is_a_no_op() {
    NutritionTargets t = targetsWithProteinFloor(null);
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", "daily_calorie_target");

    applier.apply(d, adjust(new BigDecimal("2000")), actorUserId); // already 2000

    verify(auditRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void null_tier_is_a_no_op() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", null);

    applier.apply(d, adjust(new BigDecimal("110.0")), actorUserId);

    verify(auditRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void missing_proposedFloor_extra_is_a_no_op() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", "protein_floor_g");

    applier.apply(d, adjust(null), actorUserId); // extras present but no proposedFloor

    verify(auditRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void null_extras_map_is_a_no_op() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", "protein_floor_g");
    DirectiveInstructionDocument noExtras =
        new DirectiveInstructionDocument("adjust_target", "x", "global", null, null);

    applier.apply(d, noExtras, actorUserId);

    verify(auditRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void unhandled_tier_is_a_no_op() {
    NutritionTargets t = targetsWithProteinFloor(new BigDecimal("90.0"));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    HealthDirective d = directive("nutrition_model", "sodium_ceiling_mg");

    applier.apply(d, adjust(new BigDecimal("1500")), actorUserId);

    verify(auditRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void previous_null_floor_writes_null_node_then_sets_value() {
    NutritionTargets t = targetsWithProteinFloor(null); // proteinFloor null
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);
    HealthDirective d = directive("nutrition_model", "protein_floor_g");

    applier.apply(d, adjust(new BigDecimal("100.0")), actorUserId);

    ArgumentCaptor<NutritionTargetsAuditLog> auditCap =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(auditCap.capture());
    assertThat(auditCap.getValue().getPreviousValueJson().isNull()).isTrue();
    assertThat(t.getProteinFloorG()).isEqualByComparingTo("100.0");
  }
}
