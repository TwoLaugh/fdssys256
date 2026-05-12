package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.domain.service.internal.NutritionServiceImpl;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for {@link NutritionServiceImpl}. Repositories and event publisher are mocked at the
 * module boundary; the real {@link TargetsMapper} (MapStruct-generated) and {@link ObjectMapper}
 * are used because they are deterministic, no-I/O, and central to behaviour.
 */
@ExtendWith(MockitoExtension.class)
class NutritionServiceImplTest {

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;
  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private IntakeAuditRepository intakeAuditRepository;
  @Mock private DailyActivityLogRepository dailyActivityLogRepository;
  @Mock private FoodMoodJournalRepository journalRepository;
  @Mock private IngredientMappingRepository ingredientMappingRepository;

  @Mock
  private com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository
      healthDirectiveRepository;

  @Mock private ApplicationEventPublisher eventPublisher;

  private final TargetsMapper mapper =
      new com.example.mealprep.nutrition.api.mapper.TargetsMapperImpl();
  private final IntakeMapper intakeMapper =
      new com.example.mealprep.nutrition.api.mapper.IntakeMapperImpl();
  private final DailyActivityMapper dailyActivityMapper =
      new com.example.mealprep.nutrition.api.mapper.DailyActivityMapperImpl();
  private final JournalMapper journalMapper = new JournalMapper() {};
  private final IngredientMappingMapper ingredientMappingMapper = new IngredientMappingMapper() {};
  private final com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper
      healthDirectiveMapper =
          new com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper() {};
  private final IntakeKeyNormaliser intakeKeyNormaliser = new IntakeKeyNormaliser();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private NutritionServiceImpl service() {
    org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    org.springframework.beans.factory.ObjectProvider<
            com.example.mealprep.nutrition.spi.DirectiveApplyTarget>
        emptyProvider =
            bf.getBeanProvider(com.example.mealprep.nutrition.spi.DirectiveApplyTarget.class);
    com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier directiveApplier =
        new com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier(
            targetsRepository,
            auditRepository,
            emptyProvider,
            eventPublisher,
            objectMapper,
            fixedClock);
    com.example.mealprep.nutrition.domain.service.internal.IntakeAggregator intakeAggregator =
        new com.example.mealprep.nutrition.domain.service.internal.IntakeAggregator(
            intakeDayRepository, targetsRepository);
    com.example.mealprep.nutrition.domain.service.internal.DivergenceDetector divergenceDetector =
        new com.example.mealprep.nutrition.domain.service.internal.DivergenceDetector(
            intakeDayRepository,
            targetsRepository,
            org.mockito.Mockito.mock(
                com.example.mealprep.nutrition.domain.repository.NutritionDivergenceStateRepository
                    .class),
            eventPublisher,
            fixedClock,
            new java.math.BigDecimal("0.15"),
            200);
    return new NutritionServiceImpl(
        targetsRepository,
        auditRepository,
        intakeDayRepository,
        intakeAuditRepository,
        dailyActivityLogRepository,
        journalRepository,
        ingredientMappingRepository,
        healthDirectiveRepository,
        mapper,
        intakeMapper,
        dailyActivityMapper,
        journalMapper,
        ingredientMappingMapper,
        healthDirectiveMapper,
        intakeKeyNormaliser,
        new com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate(),
        directiveApplier,
        intakeAggregator,
        divergenceDetector,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  // ---------------- getTargets ----------------

  @Test
  void getTargets_whenAbsent_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(service().getTargets(userId)).isEmpty();
  }

  @Test
  void getTargets_whenPresent_returnsDto() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));

    Optional<TargetsDto> dto = service().getTargets(userId);

    assertThat(dto).isPresent();
    assertThat(dto.get().userId()).isEqualTo(userId);
  }

  // ---------------- updateTargets ----------------

  @Test
  void updateTargets_whenTargetsMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service().updateTargets(userId, NutritionTestData.defaultUpdateRequest(0L), userId))
        .isInstanceOf(NutritionTargetsNotFoundException.class);
  }

  @Test
  void updateTargets_whenVersionStale_throwsOptimisticLockFailure() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity =
        NutritionTestData.targets().withUserId(userId).withVersion(2L).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));

    assertThatThrownBy(
            () ->
                service().updateTargets(userId, NutritionTestData.defaultUpdateRequest(1L), userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(targetsRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRepository, eventPublisher);
  }

  @Test
  void updateTargets_whenAllFieldsChange_writesAuditRowsAndPublishesEvent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity =
        NutritionTestData.targets().withUserId(userId).withVersion(0L).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateTargetsRequest request = NutritionTestData.defaultUpdateRequest(0L);

    TargetsDto result = service().updateTargets(userId, request, userId);

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(userId);

    // Exactly five rows: per-meal / micros / activities changed empty → populated, eatingWindow
    // from null to {enabled:false}, notes from null to "Default notes". The macro / calorie
    // scalar fields are aligned with the testdata builder's defaults so they no-op.
    ArgumentCaptor<NutritionTargetsAuditLog> auditCaptor =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository, times(5)).save(auditCaptor.capture());
    assertThat(auditCaptor.getAllValues())
        .extracting(NutritionTargetsAuditLog::getFieldPath)
        .containsExactlyInAnyOrder(
            "perMealDistribution", "microTargets", "activityAdjustments", "eatingWindow", "notes");
    assertThat(auditCaptor.getAllValues())
        .allSatisfy(
            row -> {
              assertThat(row.getActorUserId()).isEqualTo(userId);
              assertThat(row.getActorKind().name()).isEqualTo("USER");
              assertThat(row.getSourceDirectiveId()).isNull();
            });

    ArgumentCaptor<NutritionTargetsChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    NutritionTargetsChangedEvent event = eventCaptor.getValue();
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.targetsId()).isEqualTo(entity.getId());
    assertThat(event.changedFieldPaths())
        .contains("perMealDistribution", "microTargets", "activityAdjustments", "eatingWindow");
    assertThat(event.scopeKind()).isEqualTo("nutrition-targets");
    assertThat(event.scopeId()).isEqualTo(entity.getId());
    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-05-09T10:00:00Z"));
  }

  @Test
  void updateTargets_whenNoOpRequest_writesNoAuditRowsAndPublishesNoEvent() {
    UUID userId = UUID.randomUUID();
    // Build an aggregate that already matches the default-request shape.
    NutritionTargets entity =
        NutritionTestData.targets()
            .withUserId(userId)
            .withVersion(0L)
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.BREAKFAST,
                500,
                java.math.BigDecimal.valueOf(30.0))
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH,
                600,
                java.math.BigDecimal.valueOf(40.0))
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.DINNER,
                700,
                java.math.BigDecimal.valueOf(40.0))
            .withPerMeal(
                com.example.mealprep.nutrition.domain.entity.MealSlot.SNACKS,
                200,
                java.math.BigDecimal.valueOf(10.0))
            .withMicro("iron_mg", java.math.BigDecimal.valueOf(18.0))
            .withMicro("vitamin_d_iu", java.math.BigDecimal.valueOf(800.0))
            .withActivity(
                com.example.mealprep.nutrition.domain.entity.ActivityLevel.REST_DAY, -200, -30)
            .withActivity(
                com.example.mealprep.nutrition.domain.entity.ActivityLevel.TRAINING_DAY, 300, 50)
            .withEatingWindow(false)
            .build();
    entity.setNotes("Default notes");

    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));

    UpdateTargetsRequest request = NutritionTestData.defaultUpdateRequest(0L);

    service().updateTargets(userId, request, userId);

    // No audit rows, no event, no save-and-flush.
    verify(auditRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
    verify(targetsRepository, never()).saveAndFlush(any());
  }
}
