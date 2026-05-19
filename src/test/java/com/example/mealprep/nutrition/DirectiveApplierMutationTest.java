package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier;
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
 * Mutation killers for {@link DirectiveApplier}: the carbs / fat / fibre {@code writeAudit(...)}
 * calls (lines 150 / 159 / 168) survived because the existing {@code DirectiveApplierTest} only
 * asserted the published event and the mutated field for those three tiers — never that an audit
 * row with the right {@code fieldPath} and previous/next values was actually persisted. Removing
 * the {@code writeAudit} call (VoidMethodCall) still set the field + published the event, so it was
 * invisible. These tests capture the saved {@link NutritionTargetsAuditLog} for each tier.
 */
@ExtendWith(MockitoExtension.class)
class DirectiveApplierMutationTest {

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;

  @SuppressWarnings("unchecked")
  private final ObjectProvider<com.example.mealprep.nutrition.spi.DirectiveApplyTarget>
      preferenceProvider = org.mockito.Mockito.mock(ObjectProvider.class);

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

  private HealthDirective directive(String tier) {
    return HealthDirective.builder()
        .id(directiveId)
        .userId(userId)
        .mapsToModel("nutrition_model")
        .mapsToTier(tier)
        .temporary(true)
        .autoExpiresAt(fixedNow.plusSeconds(3600))
        .build();
  }

  private DirectiveInstructionDocument adjust(BigDecimal proposedFloor) {
    Map<String, JsonNode> extras = Map.of("proposedFloor", objectMapper.valueToTree(proposedFloor));
    return new DirectiveInstructionDocument("adjust_target", "x", "global", null, extras);
  }

  private NutritionTargets targetsWithFloors() {
    return NutritionTestData.targets()
        .withUserId(userId)
        .withProteinFloor(new BigDecimal("90.0"))
        .withCarbsFloor(new BigDecimal("200.0"))
        .withFatFloor(new BigDecimal("60.0"))
        .withFibreFloor(new BigDecimal("25.0"))
        .build();
  }

  private NutritionTargetsAuditLog captureAudit() {
    ArgumentCaptor<NutritionTargetsAuditLog> cap =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(cap.capture());
    return cap.getValue();
  }

  @Test
  void adjustCarbsFloor_writesAuditRow_withCarbsFieldPathAndPrevNext() {
    NutritionTargets t = targetsWithFloors();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);

    applier.apply(directive("carbs_floor_g"), adjust(new BigDecimal("210.0")), actorUserId);

    NutritionTargetsAuditLog audit = captureAudit();
    assertThat(audit.getFieldPath()).isEqualTo("carbs.floorG");
    assertThat(audit.getPreviousValueJson().decimalValue()).isEqualByComparingTo("200.0");
    assertThat(audit.getNewValueJson().decimalValue()).isEqualByComparingTo("210.0");
    assertThat(t.getCarbsFloorG()).isEqualByComparingTo("210.0");
  }

  @Test
  void adjustFatFloor_writesAuditRow_withFatFieldPathAndPrevNext() {
    NutritionTargets t = targetsWithFloors();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);

    applier.apply(directive("fat_floor_g"), adjust(new BigDecimal("70.0")), actorUserId);

    NutritionTargetsAuditLog audit = captureAudit();
    assertThat(audit.getFieldPath()).isEqualTo("fat.floorG");
    assertThat(audit.getPreviousValueJson().decimalValue()).isEqualByComparingTo("60.0");
    assertThat(audit.getNewValueJson().decimalValue()).isEqualByComparingTo("70.0");
    assertThat(t.getFatFloorG()).isEqualByComparingTo("70.0");
  }

  @Test
  void adjustFibreFloor_writesAuditRow_withFibreFieldPathAndPrevNext() {
    NutritionTargets t = targetsWithFloors();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(t));
    when(targetsRepository.saveAndFlush(t)).thenReturn(t);

    applier.apply(directive("fibre_floor_g"), adjust(new BigDecimal("30.0")), actorUserId);

    NutritionTargetsAuditLog audit = captureAudit();
    assertThat(audit.getFieldPath()).isEqualTo("fibre.floorG");
    assertThat(audit.getPreviousValueJson().decimalValue()).isEqualByComparingTo("25.0");
    assertThat(audit.getNewValueJson().decimalValue()).isEqualByComparingTo("30.0");
    assertThat(t.getFibreFloorG()).isEqualByComparingTo("30.0");
  }
}
