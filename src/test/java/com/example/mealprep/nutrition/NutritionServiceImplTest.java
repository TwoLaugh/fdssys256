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

  @Mock
  private com.example.mealprep.provisions.domain.service.ProvisionUpdateService
      provisionUpdateService;

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
        new com.example.mealprep.nutrition.domain.service.internal.FeedbackTargetResolver(),
        new com.example.mealprep.nutrition.config.FeedbackAdjustmentProperties(
            new java.math.BigDecimal("0.05"),
            new java.math.BigDecimal("0.10"),
            new java.math.BigDecimal("0.20"),
            1000),
        org.mockito.Mockito.mock(
            com.example.mealprep.nutrition.domain.repository.DriDefaultRepository.class),
        provisionUpdateService,
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

  @Test
  void getUserIdsWithTargets_delegatesToRepo() {
    UUID u1 = UUID.randomUUID();
    UUID u2 = UUID.randomUUID();
    when(targetsRepository.findDistinctUserIds()).thenReturn(java.util.List.of(u1, u2));

    assertThat(service().getUserIdsWithTargets()).containsExactly(u1, u2);
  }

  // ---------------- updateTargets (upsert: create-leg) ----------------

  @Test
  void updateTargets_whenAbsentAndVersionZero_createsRowFromRequestValues() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateTargetsRequest request = NutritionTestData.defaultUpdateRequest(0L);

    TargetsDto result = service().updateTargets(userId, request, userId);

    ArgumentCaptor<NutritionTargets> createdCaptor =
        ArgumentCaptor.forClass(NutritionTargets.class);
    verify(targetsRepository).saveAndFlush(createdCaptor.capture());
    NutritionTargets created = createdCaptor.getValue();
    assertThat(created.getUserId()).isEqualTo(userId);
    assertThat(created.getId()).isNotNull();
    // The created row carries the USER's request values — NOT invented generic defaults
    // (the prior auto-seed used 150 g protein; the request supplies 120 g).
    assertThat(created.getGoal().name()).isEqualTo("MAINTAIN");
    assertThat(created.getDailyCalorieTarget()).isEqualTo(2000);
    assertThat(created.getProteinTargetG()).isEqualByComparingTo("120.0");
    assertThat(created.getCarbsTargetG()).isEqualByComparingTo("250.0");
    assertThat(created.getFatTargetG()).isEqualByComparingTo("70.0");
    assertThat(created.getFibreTargetG()).isEqualByComparingTo("30.0");
    assertThat(created.getSatFatTargetG()).isEqualByComparingTo("20.0");
    assertThat(created.getNotes()).isEqualTo("Default notes");
    // The user-supplied children are persisted (not empty defaults).
    assertThat(created.getPerMealDistribution()).hasSize(4);
    assertThat(created.getMicroTargets()).hasSize(2);
    assertThat(created.getActivityAdjustments()).hasSize(2);

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(userId);

    // A create IS a genuine user write: it is audited and publishes a change event.
    verify(auditRepository, org.mockito.Mockito.atLeastOnce())
        .save(any(NutritionTargetsAuditLog.class));
    verify(eventPublisher).publishEvent(any(NutritionTargetsChangedEvent.class));
  }

  @Test
  void updateTargets_whenAbsentAndVersionNonZero_throwsOptimisticLockFailure() {
    // expectedVersion-on-create contract: a non-existent row can only be created at version 0.
    // A non-zero expectedVersion against a missing row is an optimistic-lock mismatch, never a
    // silent create.
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service().updateTargets(userId, NutritionTestData.defaultUpdateRequest(3L), userId))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(targetsRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRepository, eventPublisher);
  }

  // ---------------- updateTargets (upsert: update-leg) ----------------

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

  // ---------------- editIntakeManually (slot transition guard) ----------------
  // nutrition-intake-override-repair: edit legal from PENDING and from OVERRIDDEN with
  // needsAiParse=true (parse-failed override repair); every other decided state → 422.

  private static com.example.mealprep.nutrition.domain.entity.IntakeSlot intakeSlot(
      com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus status, boolean needsAiParse) {
    return com.example.mealprep.nutrition.domain.entity.IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH)
        .plannedCalories(600)
        .actualStatus(status)
        .needsAiParse(needsAiParse)
        .build();
  }

  private static com.example.mealprep.nutrition.domain.entity.IntakeDay intakeDayWith(
      UUID userId,
      java.time.LocalDate onDate,
      com.example.mealprep.nutrition.domain.entity.IntakeSlot slot) {
    com.example.mealprep.nutrition.domain.entity.IntakeDay day =
        com.example.mealprep.nutrition.domain.entity.IntakeDay.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(onDate)
            .build();
    day.addSlot(slot);
    return day;
  }

  private static com.example.mealprep.nutrition.api.dto.IntakeEntryDto editEntry() {
    return new com.example.mealprep.nutrition.api.dto.IntakeEntryDto(
        420,
        java.math.BigDecimal.valueOf(26.0),
        java.math.BigDecimal.valueOf(52.0),
        java.math.BigDecimal.valueOf(12.0),
        java.math.BigDecimal.valueOf(6.0),
        null);
  }

  @Test
  void editIntake_fromPending_transitionsToEdited() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);
    com.example.mealprep.nutrition.domain.entity.IntakeSlot slot =
        intakeSlot(com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.PENDING, false);
    com.example.mealprep.nutrition.domain.entity.IntakeDay day =
        intakeDayWith(userId, onDate, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, onDate)).thenReturn(Optional.of(day));
    when(intakeDayRepository.saveAndFlush(day)).thenReturn(day);

    service()
        .editIntakeManually(
            userId,
            onDate,
            com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH,
            editEntry());

    assertThat(slot.getActualStatus())
        .isEqualTo(com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.EDITED);
    assertThat(slot.getActualCalories()).isEqualTo(420);
    assertThat(slot.isNeedsAiParse()).isFalse();
    verify(intakeAuditRepository)
        .save(any(com.example.mealprep.nutrition.domain.entity.IntakeAuditLog.class));
    verify(eventPublisher)
        .publishEvent(any(com.example.mealprep.nutrition.event.IntakeLoggedEvent.class));
  }

  @Test
  void editIntake_fromOverriddenParseFailed_repairsToEdited_retainsFreeText() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);
    com.example.mealprep.nutrition.domain.entity.IntakeSlot slot =
        intakeSlot(com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.OVERRIDDEN, true);
    slot.setOverrideFreeText("a cheese sandwich");
    Instant overriddenAt = Instant.parse("2026-05-09T08:30:00Z");
    slot.setOverriddenAt(overriddenAt);
    slot.setActualCalories(0);
    com.example.mealprep.nutrition.domain.entity.IntakeDay day =
        intakeDayWith(userId, onDate, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, onDate)).thenReturn(Optional.of(day));
    when(intakeDayRepository.saveAndFlush(day)).thenReturn(day);

    service()
        .editIntakeManually(
            userId,
            onDate,
            com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH,
            editEntry());

    // Repaired: EDITED + values written + flag cleared; provenance retained.
    assertThat(slot.getActualStatus())
        .isEqualTo(com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.EDITED);
    assertThat(slot.getActualCalories()).isEqualTo(420);
    assertThat(slot.isNeedsAiParse()).isFalse();
    assertThat(slot.getOverrideFreeText()).isEqualTo("a cheese sandwich");
    assertThat(slot.getOverriddenAt()).isEqualTo(overriddenAt);

    // EDIT audit row snapshots the parse-failed OVERRIDDEN state it repaired.
    ArgumentCaptor<com.example.mealprep.nutrition.domain.entity.IntakeAuditLog> auditCaptor =
        ArgumentCaptor.forClass(com.example.mealprep.nutrition.domain.entity.IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    com.example.mealprep.nutrition.domain.entity.IntakeAuditLog audit = auditCaptor.getValue();
    assertThat(audit.getAction())
        .isEqualTo(com.example.mealprep.nutrition.domain.entity.IntakeAuditAction.EDIT);
    assertThat(audit.getPreviousValueJson().get("status").asText()).isEqualTo("OVERRIDDEN");
    assertThat(audit.getPreviousValueJson().get("needsAiParse").asBoolean()).isTrue();
    assertThat(audit.getPreviousValueJson().get("freeText").asText())
        .isEqualTo("a cheese sandwich");
    assertThat(audit.getPreviousValueJson().get("calories").asInt()).isZero();
    assertThat(audit.getNewValueJson().get("status").asText()).isEqualTo("EDITED");
    assertThat(audit.getNewValueJson().get("calories").asInt()).isEqualTo(420);
    assertThat(audit.getNewValueJson().get("needsAiParse").asBoolean()).isFalse();

    verify(eventPublisher)
        .publishEvent(any(com.example.mealprep.nutrition.event.IntakeLoggedEvent.class));
  }

  @Test
  void editIntake_fromOverriddenParseSuccess_throwsNotEditable() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);
    com.example.mealprep.nutrition.domain.entity.IntakeSlot slot =
        intakeSlot(com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.OVERRIDDEN, false);
    com.example.mealprep.nutrition.domain.entity.IntakeDay day =
        intakeDayWith(userId, onDate, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, onDate)).thenReturn(Optional.of(day));

    assertThatThrownBy(
            () ->
                service()
                    .editIntakeManually(
                        userId,
                        onDate,
                        com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH,
                        editEntry()))
        .isInstanceOf(com.example.mealprep.nutrition.exception.IntakeSlotNotEditableException.class)
        .hasMessageContaining("successful parse")
        .satisfies(
            ex -> {
              var notEditable =
                  (com.example.mealprep.nutrition.exception.IntakeSlotNotEditableException) ex;
              assertThat(notEditable.currentStatus())
                  .isEqualTo(
                      com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.OVERRIDDEN);
              assertThat(notEditable.needsAiParse()).isFalse();
            });

    // Slot untouched, nothing persisted, no audit, no event — the guard fires first.
    assertThat(slot.getActualStatus())
        .isEqualTo(com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.OVERRIDDEN);
    verify(intakeDayRepository, never()).saveAndFlush(any());
    verifyNoInteractions(intakeAuditRepository, eventPublisher);
  }

  @Test
  void editIntake_fromTerminalStates_throwsNotEditable() {
    // CONFIRMED / EDITED / SKIPPED are terminal for edit — no backwards transitions.
    for (com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus terminal :
        java.util.List.of(
            com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.CONFIRMED,
            com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.EDITED,
            com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus.SKIPPED)) {
      UUID userId = UUID.randomUUID();
      java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);
      com.example.mealprep.nutrition.domain.entity.IntakeSlot slot = intakeSlot(terminal, false);
      com.example.mealprep.nutrition.domain.entity.IntakeDay day =
          intakeDayWith(userId, onDate, slot);
      when(intakeDayRepository.findByUserIdAndOnDate(userId, onDate)).thenReturn(Optional.of(day));

      assertThatThrownBy(
              () ->
                  service()
                      .editIntakeManually(
                          userId,
                          onDate,
                          com.example.mealprep.nutrition.domain.entity.MealSlot.LUNCH,
                          editEntry()))
          .isInstanceOf(
              com.example.mealprep.nutrition.exception.IntakeSlotNotEditableException.class)
          .hasMessageContaining(terminal.name());
      assertThat(slot.getActualStatus()).isEqualTo(terminal);
    }
    verify(intakeDayRepository, never()).saveAndFlush(any());
    verifyNoInteractions(intakeAuditRepository, eventPublisher);
  }

  // ---------------- logSnack pantry deduction (nutrition-01l) ----------------

  private static com.example.mealprep.nutrition.api.dto.LogSnackRequest snackRequest(
      String mappingKey, Boolean deductFromPantry) {
    return new com.example.mealprep.nutrition.api.dto.LogSnackRequest(
        "protein bar",
        mappingKey,
        java.math.BigDecimal.valueOf(60),
        240,
        java.math.BigDecimal.valueOf(20),
        java.math.BigDecimal.valueOf(20),
        java.math.BigDecimal.valueOf(8),
        null,
        null,
        com.example.mealprep.nutrition.domain.entity.IntakeSource.MANUAL,
        deductFromPantry);
  }

  private com.example.mealprep.nutrition.domain.entity.IntakeDay stubSnackDay(
      UUID userId, java.time.LocalDate onDate) {
    com.example.mealprep.nutrition.domain.entity.IntakeDay day =
        com.example.mealprep.nutrition.domain.entity.IntakeDay.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(onDate)
            .build();
    when(intakeDayRepository.findByUserIdAndOnDate(userId, onDate)).thenReturn(Optional.of(day));
    when(intakeDayRepository.saveAndFlush(day)).thenReturn(day);
    return day;
  }

  @Test
  void logSnack_deductFromPantry_deductsViaProvisionsInTheSameFlow() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);
    stubSnackDay(userId, onDate);

    service().logSnack(userId, onDate, snackRequest("chicken_breast", true));

    ArgumentCaptor<com.example.mealprep.provisions.api.dto.StandaloneConsumptionCommand> captor =
        ArgumentCaptor.forClass(
            com.example.mealprep.provisions.api.dto.StandaloneConsumptionCommand.class);
    verify(provisionUpdateService)
        .applyStandaloneConsumption(org.mockito.ArgumentMatchers.eq(userId), captor.capture());
    assertThat(captor.getValue().ingredientMappingKey()).isEqualTo("chicken_breast");
    assertThat(captor.getValue().quantity()).isEqualByComparingTo("60");
    assertThat(captor.getValue().userConfirmedDeduction()).isTrue();
    // The snack itself still persists.
    verify(intakeDayRepository).saveAndFlush(any());
  }

  @Test
  void logSnack_deductFromPantryWithoutMappingKey_rejectsLoudly() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);

    assertThatThrownBy(() -> service().logSnack(userId, onDate, snackRequest(null, true)))
        .isInstanceOf(
            com.example.mealprep.nutrition.exception.SnackDeductWithoutMappingKeyException.class);

    // Nothing persisted, nothing deducted — the request failed whole.
    verifyNoInteractions(provisionUpdateService, intakeDayRepository, intakeAuditRepository);
  }

  @Test
  void logSnack_withoutDeductFlag_neverTouchesProvisions() {
    UUID userId = UUID.randomUUID();
    java.time.LocalDate onDate = java.time.LocalDate.of(2026, 5, 9);
    stubSnackDay(userId, onDate);

    service().logSnack(userId, onDate, snackRequest("chicken_breast", false));
    service().logSnack(userId, onDate, snackRequest("chicken_breast", null));

    verifyNoInteractions(provisionUpdateService);
  }
}
