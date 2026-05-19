package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Mutation killers for {@code NutritionServiceImpl.Snapshot.diff} (lines ~625-716). The existing
 * {@code NutritionServiceImplTest} only exercised an all-fields-change request and a no-op request,
 * so the individual per-scalar-field {@code NegateConditionalsMutator} on each {@code if
 * (!bigEq(...))} / {@code if (!Objects.equals(...))} branch survived: flipping any single one of
 * them is invisible when every field changes (the changed-set is non-empty anyway and the per-field
 * granularity is never asserted). Each test here changes exactly ONE scalar field from the no-op
 * baseline and asserts that exactly that one field path appears in {@code changedFieldPaths()} —
 * negating that field's conditional then drops (or spuriously adds) the path and fails the test.
 */
@ExtendWith(MockitoExtension.class)
class NutritionTargetsDiffMutationTest {

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;
  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private IntakeAuditRepository intakeAuditRepository;
  @Mock private DailyActivityLogRepository dailyActivityLogRepository;
  @Mock private FoodMoodJournalRepository journalRepository;
  @Mock private IngredientMappingRepository ingredientMappingRepository;
  @Mock private HealthDirectiveRepository healthDirectiveRepository;
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

  private static final UUID USER = UUID.randomUUID();

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
            new BigDecimal("0.15"),
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

  /**
   * An aggregate that exactly matches {@link NutritionTestData#defaultUpdateRequest} so a request
   * built from {@code defaultUpdateRequest} with a single field tweaked diffs to exactly that one
   * field.
   */
  private NutritionTargets baselineAggregate() {
    NutritionTargets t =
        NutritionTestData.targets()
            .withUserId(USER)
            .withVersion(0L)
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30.0))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.SNACKS, 200, BigDecimal.valueOf(10.0))
            .withMicro("iron_mg", BigDecimal.valueOf(18.0))
            .withMicro("vitamin_d_iu", BigDecimal.valueOf(800.0))
            .withActivity(
                com.example.mealprep.nutrition.domain.entity.ActivityLevel.REST_DAY, -200, -30)
            .withActivity(
                com.example.mealprep.nutrition.domain.entity.ActivityLevel.TRAINING_DAY, 300, 50)
            .withEatingWindow(false)
            .build();
    t.setNotes("Default notes");
    return t;
  }

  private NutritionTargetsChangedEvent applyAndCaptureEvent(UpdateTargetsRequest request) {
    NutritionTargets entity = baselineAggregate();
    when(targetsRepository.findByUserId(USER)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().updateTargets(USER, request, USER);

    ArgumentCaptor<NutritionTargetsChangedEvent> cap =
        ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
    verify(eventPublisher).publishEvent(cap.capture());
    return cap.getValue();
  }

  private void assertExactlyOneAuditRowFor(String fieldPath) {
    ArgumentCaptor<NutritionTargetsAuditLog> cap =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(cap.capture());
    assertThat(cap.getValue().getFieldPath()).isEqualTo(fieldPath);
  }

  // ---------------- scalar single-field diffs ----------------

  @Test
  void onlyGoalChanged_yieldsGoalPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            Goal.LOSE_WEIGHT, // changed from MAINTAIN
            base.calories(),
            base.protein(),
            base.carbs(),
            base.fat(),
            base.fibre(),
            base.satFat(),
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("goal");
    assertExactlyOneAuditRowFor("goal");
  }

  @Test
  void onlyDailyCalorieTargetChanged_yieldsCalorieDailyTargetPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    CalorieTargetDto cals =
        new CalorieTargetDto(
            2222, // changed from 2000
            base.calories().toleranceUnder(),
            base.calories().toleranceOver(),
            base.calories().enforcement(),
            base.calories().direction());
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            cals,
            base.protein(),
            base.carbs(),
            base.fat(),
            base.fibre(),
            base.satFat(),
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("calories.dailyTarget");
    assertExactlyOneAuditRowFor("calories.dailyTarget");
  }

  @Test
  void onlyProteinTargetChanged_yieldsProteinTargetPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    MacroTargetDto protein =
        new MacroTargetDto(
            BigDecimal.valueOf(135.0), // changed from 120.0
            base.protein().floorG(),
            base.protein().enforcement(),
            base.protein().direction());
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            protein,
            base.carbs(),
            base.fat(),
            base.fibre(),
            base.satFat(),
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("protein.targetG");
    assertExactlyOneAuditRowFor("protein.targetG");
  }

  @Test
  void onlyCarbsTargetChanged_yieldsCarbsTargetPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    MacroTargetDto carbs =
        new MacroTargetDto(
            BigDecimal.valueOf(275.0), // changed from 250.0
            base.carbs().floorG(),
            base.carbs().enforcement(),
            base.carbs().direction());
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            carbs,
            base.fat(),
            base.fibre(),
            base.satFat(),
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("carbs.targetG");
    assertExactlyOneAuditRowFor("carbs.targetG");
  }

  @Test
  void onlyFatDirectionChanged_yieldsFatDirectionPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    MacroTargetDto fat =
        new MacroTargetDto(
            base.fat().targetG(),
            base.fat().floorG(),
            base.fat().enforcement(),
            EnforcementDirection.UPPER_LIMIT); // changed from BOTH_BOUNDED
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            base.carbs(),
            fat,
            base.fibre(),
            base.satFat(),
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("fat.direction");
    assertExactlyOneAuditRowFor("fat.direction");
  }

  @Test
  void onlyFibreEnforcementChanged_yieldsFibreEnforcementPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    MacroTargetDto fibre =
        new MacroTargetDto(
            base.fibre().targetG(),
            base.fibre().floorG(),
            "weekly_average", // changed from "daily_floor"
            base.fibre().direction());
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            base.carbs(),
            base.fat(),
            fibre,
            base.satFat(),
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("fibre.enforcement");
    assertExactlyOneAuditRowFor("fibre.enforcement");
  }

  @Test
  void onlyNotesChanged_yieldsNotesPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            base.carbs(),
            base.fat(),
            base.fibre(),
            base.satFat(),
            "A brand-new note", // changed from "Default notes"
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("notes");
    assertExactlyOneAuditRowFor("notes");
  }

  @Test
  void onlySatFatTargetChanged_yieldsSatFatTargetPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    MacroTargetDto satFat =
        new MacroTargetDto(
            BigDecimal.valueOf(25.0), // changed from 20.0
            base.satFat().floorG(),
            base.satFat().enforcement(),
            base.satFat().direction());
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            base.carbs(),
            base.fat(),
            base.fibre(),
            satFat,
            base.notes(),
            base.perMealDistribution(),
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("satFat.targetG");
    assertExactlyOneAuditRowFor("satFat.targetG");
  }

  @Test
  void onlyPerMealDistributionChanged_yieldsPerMealPathOnly() {
    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    List<com.example.mealprep.nutrition.api.dto.PerMealDistributionDto> pm =
        new java.util.ArrayList<>(base.perMealDistribution());
    // Bump BREAKFAST calories 500 -> 520; everything else identical.
    pm.set(
        0,
        new com.example.mealprep.nutrition.api.dto.PerMealDistributionDto(
            MealSlot.BREAKFAST, 520, BigDecimal.valueOf(30.0)));
    UpdateTargetsRequest req =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            base.carbs(),
            base.fat(),
            base.fibre(),
            base.satFat(),
            base.notes(),
            pm,
            base.microTargets(),
            base.eatingWindow(),
            base.activityAdjustments(),
            0L);

    NutritionTargetsChangedEvent ev = applyAndCaptureEvent(req);

    assertThat(ev.changedFieldPaths()).containsExactly("perMealDistribution");
    assertExactlyOneAuditRowFor("perMealDistribution");
  }
}
