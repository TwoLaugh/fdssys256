package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.ActivityAdjustmentDto;
import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.EatingWindowDto;
import com.example.mealprep.nutrition.api.dto.FeedbackTargetAdjustment;
import com.example.mealprep.nutrition.api.dto.FloorViolationDto;
import com.example.mealprep.nutrition.api.dto.IngredientLookupRequest;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeListFilter;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.entity.ActorKind;
import com.example.mealprep.nutrition.domain.entity.AdjustmentDirection;
import com.example.mealprep.nutrition.domain.entity.AdjustmentMagnitude;
import com.example.mealprep.nutrition.domain.entity.DailyActivityLog;
import com.example.mealprep.nutrition.domain.entity.DriDefault;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.FoodMoodJournalEntry;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.HealthDirective;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditAction;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditLog;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.DriDefaultRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate;
import com.example.mealprep.nutrition.domain.service.internal.DivergenceDetector;
import com.example.mealprep.nutrition.domain.service.internal.IntakeAggregator;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.domain.service.internal.NutritionServiceImpl;
import com.example.mealprep.nutrition.event.HealthDirectiveAcceptedEvent;
import com.example.mealprep.nutrition.event.HealthDirectiveReceivedEvent;
import com.example.mealprep.nutrition.event.IngredientMappingCorrectedEvent;
import com.example.mealprep.nutrition.event.IntakeLoggedEvent;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.exception.DuplicateHealthDirectiveException;
import com.example.mealprep.nutrition.exception.HealthDirectiveAlreadyDecidedException;
import com.example.mealprep.nutrition.exception.HealthDirectiveNotFoundException;
import com.example.mealprep.nutrition.exception.HealthDirectiveSafetyGateBlockedException;
import com.example.mealprep.nutrition.exception.IngredientMappingNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSnackNotFoundException;
import com.example.mealprep.nutrition.exception.InvalidIntakeRangeException;
import com.example.mealprep.nutrition.exception.InvalidWeekStartException;
import com.example.mealprep.nutrition.exception.JournalEntryNotFoundException;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit test for the {@link NutritionServiceImpl} intake, directive, ingredient, journal and
 * recipe-calculation flows. Same fixture approach as {@code NutritionServiceImplTest}: repositories
 * and the event publisher are mocked; the MapStruct mappers and {@link ObjectMapper} are real. The
 * {@link DirectiveApplier} and {@link DivergenceDetector} collaborators are mocked here so their
 * invocation from the write flows is verifiable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NutritionServiceImplIntakeFlowsTest {

  private static final Instant NOW = Instant.parse("2026-05-09T10:00:00Z");
  private static final LocalDate ON_DATE = LocalDate.of(2026, 5, 9);

  @Mock private NutritionTargetsRepository targetsRepository;
  @Mock private NutritionTargetsAuditRepository auditRepository;
  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private IntakeAuditRepository intakeAuditRepository;
  @Mock private DailyActivityLogRepository dailyActivityLogRepository;
  @Mock private FoodMoodJournalRepository journalRepository;
  @Mock private IngredientMappingRepository ingredientMappingRepository;
  @Mock private HealthDirectiveRepository healthDirectiveRepository;
  @Mock private DriDefaultRepository driDefaultRepository;
  @Mock private DirectiveApplier directiveApplier;
  @Mock private DivergenceDetector divergenceDetector;

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
  private final HealthDirectiveMapper healthDirectiveMapper = new HealthDirectiveMapper() {};
  private final IntakeKeyNormaliser intakeKeyNormaliser = new IntakeKeyNormaliser();
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

  private NutritionServiceImpl service() {
    IntakeAggregator intakeAggregator =
        new IntakeAggregator(intakeDayRepository, targetsRepository);
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
        new DirectiveSafetyGate(),
        directiveApplier,
        intakeAggregator,
        divergenceDetector,
        new com.example.mealprep.nutrition.domain.service.internal.FeedbackTargetResolver(),
        new com.example.mealprep.nutrition.config.FeedbackAdjustmentProperties(
            new BigDecimal("0.05"), new BigDecimal("0.10"), new BigDecimal("0.20"), 1000),
        driDefaultRepository,
        provisionUpdateService,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  // ---------------- Shared fixtures ----------------

  private static IntakeDay day(UUID userId, IntakeSlot slot) {
    IntakeDay day =
        IntakeDay.builder().id(UUID.randomUUID()).userId(userId).onDate(ON_DATE).build();
    if (slot != null) {
      day.addSlot(slot);
    }
    return day;
  }

  private IntakeSlot plannedLunchSlot() {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(MealSlot.LUNCH)
        .plannedCalories(600)
        .plannedProteinG(BigDecimal.valueOf(40.0))
        .plannedCarbsG(BigDecimal.valueOf(70.0))
        .plannedFatG(BigDecimal.valueOf(20.0))
        .plannedFibreG(BigDecimal.valueOf(10.0))
        .plannedMicros(objectMapper.createObjectNode().put("saturated_fat_g", 3.5))
        .actualStatus(IntakeSlotStatus.PENDING)
        .needsAiParse(true)
        .build();
  }

  private IntakeSnack snack(String freeText, int calories) {
    return IntakeSnack.builder()
        .id(UUID.randomUUID())
        .freeText(freeText)
        .quantityG(BigDecimal.valueOf(30.0))
        .calories(calories)
        .proteinG(BigDecimal.valueOf(7.0))
        .carbsG(BigDecimal.valueOf(6.0))
        .fatG(BigDecimal.valueOf(15.0))
        .fibreG(BigDecimal.valueOf(3.0))
        .source(IntakeSource.MANUAL)
        .loggedAt(NOW)
        .build();
  }

  private static DirectiveInstructionDocument adjustProteinFloor(int proposedFloor) {
    return NutritionTestData.instructionFor(
        "adjust_target",
        "protein_floor_g",
        NutritionTestData.instructionExtras("proposedFloor", new IntNode(proposedFloor)));
  }

  private static HealthDirective pendingDirective(
      UUID userId, DirectiveInstructionDocument instruction) {
    return HealthDirective.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .externalDirectiveId("ext-1")
        .sourcePlatform("apple-health")
        .receivedAt(NOW)
        .status(DirectiveStatus.PENDING_REVIEW)
        .directiveType(DirectiveType.TARGET_ADJUSTMENT)
        .instructionPayload(instruction)
        .mapsToModel("nutrition_model")
        .mapsToTier("protein_floor_g")
        .temporary(false)
        .build();
  }

  private static IngredientMapping mapping(
      String searchTerm, IngredientNutritionDocument doc, BigDecimal confidence) {
    return IngredientMapping.builder()
        .id(UUID.randomUUID())
        .searchTerm(searchTerm)
        .source(IngredientMappingSource.MANUAL)
        .nutritionPer100g(doc)
        .confidence(confidence)
        .needsReview(false)
        .build();
  }

  // ---------------- getTargetsAuditLog ----------------

  @Test
  void getTargetsAuditLog_whenTargetsExist_mapsRowsToDtos() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    NutritionTargetsAuditLog row =
        new NutritionTargetsAuditLog(
            UUID.randomUUID(),
            entity.getId(),
            userId,
            ActorKind.USER,
            null,
            "notes",
            objectMapper.nullNode(),
            objectMapper.valueToTree("hello"),
            NOW);
    when(auditRepository.findByTargetsIdOrderByOccurredAtDesc(
            eq(entity.getId()), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));

    var page = service().getTargetsAuditLog(userId, PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).fieldPath()).isEqualTo("notes");
  }

  // ---------------- initialiseTargets ----------------

  @Test
  void initialiseTargets_seedsUnsuppliedDriMicros_capsSodium_auditsAndPublishes() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(driDefaultRepository.findByAgeGroupAndSex("31-50", "female"))
        .thenReturn(
            List.of(
                driRow("iron_mg", "18"),
                driRow("calcium_mg", "1000"),
                driRow("sodium_mg", "1500")));

    var result = service().initialiseTargets(userId, NutritionTestData.defaultUpdateRequest(0L));

    ArgumentCaptor<NutritionTargets> captor = ArgumentCaptor.forClass(NutritionTargets.class);
    verify(targetsRepository).saveAndFlush(captor.capture());
    List<MicroTarget> micros = captor.getValue().getMicroTargets();
    // Request supplies iron + vitamin D; DRI seeds calcium + sodium; iron is NOT re-seeded.
    assertThat(micros).hasSize(4);
    MicroTarget iron = microByKey(micros, "iron_mg");
    assertThat(iron.getTargetValue()).isEqualByComparingTo("18.0");
    assertThat(iron.getSourcePreference()).isNull();
    MicroTarget calcium = microByKey(micros, "calcium_mg");
    assertThat(calcium.getTargetValue()).isEqualByComparingTo("1000");
    assertThat(calcium.getUpperLimit()).isNull();
    assertThat(calcium.getSourcePreference()).isEqualTo("dri_default");
    assertThat(calcium.isHardFloor()).isFalse();
    MicroTarget sodium = microByKey(micros, "sodium_mg");
    assertThat(sodium.getTargetValue()).isNull();
    assertThat(sodium.getUpperLimit()).isEqualByComparingTo("2300");

    verify(auditRepository, atLeastOnce()).save(any(NutritionTargetsAuditLog.class));
    ArgumentCaptor<NutritionTargetsChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().userId()).isEqualTo(userId);
    assertThat(eventCaptor.getValue().changedFieldPaths()).contains("microTargets");
    assertThat(eventCaptor.getValue().occurredAt()).isEqualTo(NOW);

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(userId);
  }

  private static DriDefault driRow(String microName, String rda) {
    return DriDefault.builder()
        .id(UUID.randomUUID())
        .ageGroup("31-50")
        .sex("female")
        .lifeStage("NONE")
        .microName(microName)
        .rdaValue(new BigDecimal(rda))
        .unit("mg")
        .build();
  }

  private static MicroTarget microByKey(List<MicroTarget> micros, String key) {
    return micros.stream().filter(m -> key.equals(m.getNutrientKey())).findFirst().orElseThrow();
  }

  // ---------------- updateTargets: create-leg extras ----------------

  @Test
  void updateTargets_createLeg_carriesSatFatDirectionAndEatingWindow() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().updateTargets(userId, NutritionTestData.defaultUpdateRequest(0L), userId);

    ArgumentCaptor<NutritionTargets> captor = ArgumentCaptor.forClass(NutritionTargets.class);
    verify(targetsRepository).saveAndFlush(captor.capture());
    NutritionTargets created = captor.getValue();
    assertThat(created.getSatFatTargetG()).isEqualByComparingTo("20.0");
    assertThat(created.getSatFatDirection()).isEqualTo(EnforcementDirection.UPPER_LIMIT);
    assertThat(created.getEatingWindow()).isNotNull();
    assertThat(created.getEatingWindow().isEnabled()).isFalse();
  }

  // ---------------- updateTargets: update-leg ----------------

  @Test
  void updateTargets_whenEveryFieldDiffers_appliesAllScalarsAndChildren() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity =
        NutritionTestData.targets().withUserId(userId).withVersion(0L).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    UpdateTargetsRequest request =
        new UpdateTargetsRequest(
            Goal.LOSE_WEIGHT,
            new CalorieTargetDto(1800, 50, 75, "daily_band", EnforcementDirection.LOWER_FLOOR),
            new MacroTargetDto(
                BigDecimal.valueOf(130.0),
                BigDecimal.valueOf(100.0),
                "weekly_average",
                EnforcementDirection.BOTH_BOUNDED,
                false),
            new MacroTargetDto(
                BigDecimal.valueOf(200.0),
                BigDecimal.valueOf(150.0),
                "daily_floor",
                EnforcementDirection.LOWER_FLOOR,
                false),
            new MacroTargetDto(
                BigDecimal.valueOf(60.0),
                BigDecimal.valueOf(40.0),
                "daily_floor",
                EnforcementDirection.LOWER_FLOOR,
                false),
            new MacroTargetDto(
                BigDecimal.valueOf(35.0),
                BigDecimal.valueOf(25.0),
                "weekly_average",
                EnforcementDirection.BOTH_BOUNDED,
                false),
            new MacroTargetDto(
                BigDecimal.valueOf(15.0), null, null, EnforcementDirection.LOWER_FLOOR, false),
            "cutting",
            List.of(new PerMealDistributionDto(MealSlot.LUNCH, 900, BigDecimal.valueOf(50.0))),
            List.of(
                new MicroTargetDto("zinc_mg", BigDecimal.valueOf(11.0), null, null, null, false)),
            new EatingWindowDto(true, LocalTime.of(8, 0), LocalTime.of(16, 0), "16:8"),
            List.of(new ActivityAdjustmentDto(ActivityLevel.REST_DAY, -100, -20)),
            0L);

    var result = service().updateTargets(userId, request, userId);

    assertThat(result).isNotNull();
    assertThat(entity.getGoal()).isEqualTo(Goal.LOSE_WEIGHT);
    assertThat(entity.getDailyCalorieTarget()).isEqualTo(1800);
    assertThat(entity.getCalorieToleranceUnder()).isEqualTo(50);
    assertThat(entity.getCalorieToleranceOver()).isEqualTo(75);
    assertThat(entity.getCalorieEnforcement()).isEqualTo("daily_band");
    assertThat(entity.getCalorieDirection()).isEqualTo(EnforcementDirection.LOWER_FLOOR);

    assertThat(entity.getProteinTargetG()).isEqualByComparingTo("130.0");
    assertThat(entity.getProteinFloorG()).isEqualByComparingTo("100.0");
    assertThat(entity.getProteinEnforcement()).isEqualTo("weekly_average");
    assertThat(entity.getProteinDirection()).isEqualTo(EnforcementDirection.BOTH_BOUNDED);
    assertThat(entity.isProteinHardFloor()).isFalse();

    assertThat(entity.getCarbsTargetG()).isEqualByComparingTo("200.0");
    assertThat(entity.getCarbsFloorG()).isEqualByComparingTo("150.0");
    assertThat(entity.getCarbsEnforcement()).isEqualTo("daily_floor");
    assertThat(entity.getCarbsDirection()).isEqualTo(EnforcementDirection.LOWER_FLOOR);
    assertThat(entity.isCarbsHardFloor()).isFalse();

    assertThat(entity.getFatTargetG()).isEqualByComparingTo("60.0");
    assertThat(entity.getFatFloorG()).isEqualByComparingTo("40.0");
    assertThat(entity.getFatEnforcement()).isEqualTo("daily_floor");
    assertThat(entity.getFatDirection()).isEqualTo(EnforcementDirection.LOWER_FLOOR);
    assertThat(entity.isFatHardFloor()).isFalse();

    assertThat(entity.getFibreTargetG()).isEqualByComparingTo("35.0");
    assertThat(entity.getFibreFloorG()).isEqualByComparingTo("25.0");
    assertThat(entity.getFibreEnforcement()).isEqualTo("weekly_average");
    assertThat(entity.getFibreDirection()).isEqualTo(EnforcementDirection.BOTH_BOUNDED);
    assertThat(entity.isFibreHardFloor()).isFalse();

    assertThat(entity.getSatFatTargetG()).isEqualByComparingTo("15.0");
    assertThat(entity.getSatFatDirection()).isEqualTo(EnforcementDirection.LOWER_FLOOR);
    assertThat(entity.getNotes()).isEqualTo("cutting");

    assertThat(entity.getPerMealDistribution()).hasSize(1);
    assertThat(entity.getPerMealDistribution().get(0).getMealSlot()).isEqualTo(MealSlot.LUNCH);
    assertThat(entity.getPerMealDistribution().get(0).getCalorieTarget()).isEqualTo(900);
    assertThat(entity.getMicroTargets()).hasSize(1);
    assertThat(entity.getMicroTargets().get(0).getNutrientKey()).isEqualTo("zinc_mg");
    assertThat(entity.getMicroTargets().get(0).getTargetValue()).isEqualByComparingTo("11.0");
    assertThat(entity.getActivityAdjustments()).hasSize(1);
    assertThat(entity.getActivityAdjustments().get(0).getActivityLevel())
        .isEqualTo(ActivityLevel.REST_DAY);
    assertThat(entity.getActivityAdjustments().get(0).getCalorieModifier()).isEqualTo(-100);
    assertThat(entity.getEatingWindow()).isNotNull();
    assertThat(entity.getEatingWindow().isEnabled()).isTrue();
    assertThat(entity.getEatingWindow().getWindowStart()).isEqualTo(LocalTime.of(8, 0));

    // The protein floor went from unset to 100 and its audit row carries the real values.
    ArgumentCaptor<NutritionTargetsAuditLog> auditCaptor =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository, atLeastOnce()).save(auditCaptor.capture());
    NutritionTargetsAuditLog proteinFloorRow =
        auditCaptor.getAllValues().stream()
            .filter(r -> "protein.floorG".equals(r.getFieldPath()))
            .findFirst()
            .orElseThrow();
    assertThat(proteinFloorRow.getNewValueJson().asDouble()).isEqualTo(100.0);
  }

  @Test
  void updateTargets_reorderedButIdenticalChildren_isNoOp() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity =
        NutritionTestData.targets()
            .withUserId(userId)
            .withVersion(0L)
            .withPerMeal(MealSlot.SNACKS, 200, BigDecimal.valueOf(10.0))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30.0))
            .withMicro("vitamin_d_iu", BigDecimal.valueOf(800.0))
            .withMicro("iron_mg", BigDecimal.valueOf(18.0))
            .withActivity(ActivityLevel.TRAINING_DAY, 300, 50)
            .withActivity(ActivityLevel.REST_DAY, -200, -30)
            .withEatingWindow(false)
            .build();
    entity.setNotes("Default notes");
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));

    UpdateTargetsRequest base = NutritionTestData.defaultUpdateRequest(0L);
    var perMeal = new ArrayList<>(NutritionTestData.defaultPerMealList());
    Collections.reverse(perMeal);
    var micros = new ArrayList<>(NutritionTestData.defaultMicros());
    Collections.reverse(micros);
    var activities = new ArrayList<>(NutritionTestData.defaultActivities());
    Collections.reverse(activities);
    UpdateTargetsRequest request =
        new UpdateTargetsRequest(
            base.goal(),
            base.calories(),
            base.protein(),
            base.carbs(),
            base.fat(),
            base.fibre(),
            base.satFat(),
            base.notes(),
            perMeal,
            micros,
            base.eatingWindow(),
            activities,
            0L);

    var result = service().updateTargets(userId, request, userId);

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(userId);
    verify(auditRepository, never()).save(any());
    verifyNoInteractions(eventPublisher);
    verify(targetsRepository, never()).saveAndFlush(any());
  }

  @Test
  void updateTargets_sameSizeChildContentChange_isDetected() {
    UUID userId = UUID.randomUUID();
    // Same micro keys but iron 20 vs requested 18; eating window enabled flips true -> false.
    NutritionTargets entity =
        NutritionTestData.targets()
            .withUserId(userId)
            .withVersion(0L)
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30.0))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.SNACKS, 200, BigDecimal.valueOf(10.0))
            .withMicro("iron_mg", BigDecimal.valueOf(20.0))
            .withMicro("vitamin_d_iu", BigDecimal.valueOf(800.0))
            .withActivity(ActivityLevel.REST_DAY, -200, -30)
            .withActivity(ActivityLevel.TRAINING_DAY, 300, 50)
            .withEatingWindow(true)
            .build();
    entity.setNotes("Default notes");
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().updateTargets(userId, NutritionTestData.defaultUpdateRequest(0L), userId);

    ArgumentCaptor<NutritionTargetsAuditLog> auditCaptor =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository, times(2)).save(auditCaptor.capture());
    assertThat(auditCaptor.getAllValues())
        .extracting(NutritionTargetsAuditLog::getFieldPath)
        .containsExactlyInAnyOrder("microTargets", "eatingWindow");
    ArgumentCaptor<NutritionTargetsChangedEvent> eventCaptor =
        ArgumentCaptor.forClass(NutritionTargetsChangedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().changedFieldPaths())
        .containsExactlyInAnyOrder("microTargets", "eatingWindow");
  }

  // ---------------- applyFeedbackAdjustment ----------------

  @Test
  void feedbackAdjustment_decreaseSmall_nudgesMacroByFivePercent() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var result =
        service()
            .applyFeedbackAdjustment(
                userId,
                new FeedbackTargetAdjustment(
                    "protein_target_g",
                    AdjustmentDirection.DECREASE,
                    AdjustmentMagnitude.SMALL,
                    null,
                    "too much protein"));

    assertThat(result).isNotNull();
    assertThat(entity.getProteinTargetG()).isEqualByComparingTo("114");
    ArgumentCaptor<NutritionTargetsAuditLog> auditCaptor =
        ArgumentCaptor.forClass(NutritionTargetsAuditLog.class);
    verify(auditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getActorKind()).isEqualTo(ActorKind.FEEDBACK);
    assertThat(auditCaptor.getValue().getFieldPath()).isEqualTo("protein_target_g");
  }

  @Test
  void feedbackAdjustment_absoluteMacroValue_isAppliedWithoutRounding() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            new FeedbackTargetAdjustment(
                "carbs_target_g",
                AdjustmentDirection.INCREASE,
                AdjustmentMagnitude.SMALL,
                new BigDecimal("123.45"),
                "explicit carbs"));

    assertThat(entity.getCarbsTargetG()).isEqualByComparingTo("123.45");
  }

  @Test
  void feedbackAdjustment_calorieTargetBelowFloor_clampsToConfiguredFloor() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
    when(targetsRepository.saveAndFlush(any(NutritionTargets.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service()
        .applyFeedbackAdjustment(
            userId,
            new FeedbackTargetAdjustment(
                "calorie_target",
                AdjustmentDirection.DECREASE,
                AdjustmentMagnitude.LARGE,
                new BigDecimal("500"),
                "crash diet request"));

    assertThat(entity.getDailyCalorieTarget()).isEqualTo(1000);
  }

  @Test
  void feedbackAdjustment_microNotOptedIn_isNoOp() {
    UUID userId = UUID.randomUUID();
    NutritionTargets entity = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));

    var result =
        service()
            .applyFeedbackAdjustment(
                userId,
                new FeedbackTargetAdjustment(
                    "micro.zinc_mg",
                    AdjustmentDirection.INCREASE,
                    AdjustmentMagnitude.SMALL,
                    null,
                    "zinc feedback"));

    assertThat(result).isNotNull();
    assertThat(result.userId()).isEqualTo(userId);
    verify(auditRepository, never()).save(any());
    verify(targetsRepository, never()).saveAndFlush(any());
    verifyNoInteractions(eventPublisher);
  }

  // ---------------- Intake queries ----------------

  @Test
  void getIntakeForDay_whenPresent_returnsMappedDay() {
    UUID userId = UUID.randomUUID();
    IntakeDay day = day(userId, plannedLunchSlot());
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var dto = service().getIntakeForDay(userId, ON_DATE);

    assertThat(dto).isPresent();
    assertThat(dto.get().onDate()).isEqualTo(ON_DATE);
    assertThat(dto.get().slots()).hasSize(1);
  }

  @Test
  void getIntakeRange_acceptsThirtyFiveDaysAndMapsDays() {
    UUID userId = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 5, 1);
    LocalDate to = from.plusDays(34);
    when(intakeDayRepository.findByUserIdAndOnDateBetween(userId, from, to))
        .thenReturn(List.of(day(userId, null)));

    var days = service().getIntakeRange(userId, from, to);

    assertThat(days).hasSize(1);
  }

  @Test
  void getIntakeRange_rejectsNullFrom() {
    assertThatThrownBy(() -> service().getIntakeRange(UUID.randomUUID(), null, ON_DATE))
        .isInstanceOf(InvalidIntakeRangeException.class);
  }

  @Test
  void getIntakeRange_rejectsFromAfterTo() {
    assertThatThrownBy(
            () -> service().getIntakeRange(UUID.randomUUID(), ON_DATE, ON_DATE.minusDays(1)))
        .isInstanceOf(InvalidIntakeRangeException.class);
  }

  @Test
  void getIntakeRange_rejectsThirtySixDays() {
    LocalDate from = LocalDate.of(2026, 5, 1);
    assertThatThrownBy(() -> service().getIntakeRange(UUID.randomUUID(), from, from.plusDays(35)))
        .isInstanceOf(InvalidIntakeRangeException.class);
  }

  @Test
  void getRecentIntakeTotals_returnsOneAggregatePerDayInclusive() {
    UUID userId = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 5, 1);

    var totals = service().getRecentIntakeTotals(userId, from, from.plusDays(2));

    assertThat(totals).hasSize(3);
  }

  @Test
  void getRecentIntakeTotals_rejectsNullFrom() {
    assertThatThrownBy(() -> service().getRecentIntakeTotals(UUID.randomUUID(), null, ON_DATE))
        .isInstanceOf(InvalidIntakeRangeException.class);
  }

  @Test
  void getDailyAggregate_sumsSnackIntoActuals() {
    UUID userId = UUID.randomUUID();
    IntakeDay day = day(userId, null);
    day.addSnack(snack("protein bar", 300));
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var aggregate = service().getDailyAggregate(userId, ON_DATE);

    assertThat(aggregate).isNotNull();
    assertThat(aggregate.caloriesActualSoFar()).isEqualTo(300);
  }

  @Test
  void getWeeklyAggregate_rejectsNullAndNonMondayStart() {
    assertThatThrownBy(() -> service().getWeeklyAggregate(UUID.randomUUID(), null))
        .isInstanceOf(InvalidWeekStartException.class);
    assertThatThrownBy(
            () -> service().getWeeklyAggregate(UUID.randomUUID(), LocalDate.of(2026, 5, 5)))
        .isInstanceOf(InvalidWeekStartException.class);
  }

  @Test
  void getWeeklyAggregate_mondayStart_returnsSevenDayWindow() {
    LocalDate monday = LocalDate.of(2026, 5, 4);

    var week = service().getWeeklyAggregate(UUID.randomUUID(), monday);

    assertThat(week).isNotNull();
    assertThat(week.weekStart()).isEqualTo(monday);
    assertThat(week.perDay()).hasSize(7);
  }

  @Test
  void getIntakeAuditLog_whenDayMissing_returnsEmptyWithoutQuerying() {
    UUID userId = UUID.randomUUID();
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.empty());

    var page = service().getIntakeAuditLog(userId, ON_DATE, PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isZero();
    verifyNoInteractions(intakeAuditRepository);
  }

  @Test
  void getIntakeAuditLog_whenDayExists_mapsEntries() {
    UUID userId = UUID.randomUUID();
    IntakeDay day = day(userId, null);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));
    IntakeAuditLog row =
        new IntakeAuditLog(
            UUID.randomUUID(),
            day,
            userId,
            IntakeAuditAction.CONFIRM,
            MealSlot.LUNCH,
            null,
            objectMapper.nullNode(),
            objectMapper.nullNode(),
            NOW);
    when(intakeAuditRepository.findByIntakeDay_IdOrderByOccurredAtDesc(
            eq(day.getId()), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));

    var page = service().getIntakeAuditLog(userId, ON_DATE, PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).action()).isEqualTo(IntakeAuditAction.CONFIRM);
  }

  @Test
  void searchIntakeSlots_nullFilter_searchesUnfiltered() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    slot.setOverrideFreeText("cheese toastie");
    IntakeDay day = day(userId, slot);
    when(intakeDayRepository.searchSlots(
            eq(userId), isNull(), isNull(), eq(""), eq(false), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(slot)));

    var page = service().searchIntakeSlots(userId, null, PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    var hit = page.getContent().get(0);
    assertThat(hit.slotId()).isEqualTo(slot.getId());
    assertThat(hit.intakeDayId()).isEqualTo(day.getId());
    assertThat(hit.onDate()).isEqualTo(ON_DATE);
    assertThat(hit.mealSlot()).isEqualTo(MealSlot.LUNCH);
    assertThat(hit.overrideFreeText()).isEqualTo("cheese toastie");
  }

  @Test
  void searchIntakeSlots_withFreeTextQuery_passesQueryThrough() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    day(userId, slot);
    when(intakeDayRepository.searchSlots(
            eq(userId), isNull(), isNull(), eq("cheese"), eq(true), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(slot)));

    var page =
        service()
            .searchIntakeSlots(
                userId, new IntakeListFilter(null, null, "cheese"), PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
  }

  // ---------------- prefillFromPlan ----------------

  @Test
  void prefillFromPlan_createsDayAndSlots_auditsAndPublishes() {
    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.empty());

    var dto =
        service().prefillFromPlan(userId, ON_DATE, planId, NutritionTestData.defaultPlannedSlots());

    assertThat(dto).isNotNull();
    assertThat(dto.planId()).isEqualTo(planId);
    assertThat(dto.slots()).hasSize(3);

    ArgumentCaptor<IntakeDay> dayCaptor = ArgumentCaptor.forClass(IntakeDay.class);
    verify(intakeDayRepository).saveAndFlush(dayCaptor.capture());
    IntakeDay day = dayCaptor.getValue();
    assertThat(day.getPlanId()).isEqualTo(planId);
    assertThat(day.getSlots()).hasSize(3);
    assertThat(day.getSlots())
        .allSatisfy(s -> assertThat(s.getActualStatus()).isEqualTo(IntakeSlotStatus.PENDING));

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo(IntakeAuditAction.PREFILL);
    assertThat(auditCaptor.getValue().getPreviousValueJson().get("slotCount").asInt()).isZero();
    assertThat(auditCaptor.getValue().getNewValueJson().get("slotCount").asInt()).isEqualTo(3);

    ArgumentCaptor<IntakeLoggedEvent> eventCaptor =
        ArgumentCaptor.forClass(IntakeLoggedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().action()).isEqualTo(IntakeAuditAction.PREFILL);
    assertThat(eventCaptor.getValue().onDate()).isEqualTo(ON_DATE);
  }

  @Test
  void prefillFromPlan_existingDay_updatesPendingSlotInPlace() {
    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    IntakeSlot pending =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.BREAKFAST)
            .plannedCalories(400)
            .actualStatus(IntakeSlotStatus.PENDING)
            .build();
    IntakeDay day = day(userId, pending);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    service().prefillFromPlan(userId, ON_DATE, planId, NutritionTestData.defaultPlannedSlots());

    assertThat(day.getSlots()).hasSize(3);
    // The PENDING breakfast row is the SAME entity, refreshed to the new snapshot (no
    // delete+insert on the (day, meal_slot) unique index).
    assertThat(day.getSlots()).contains(pending);
    assertThat(pending.getPlannedCalories()).isEqualTo(500);
    assertThat(pending.getPlannedProteinG()).isEqualByComparingTo("30.0");
    assertThat(pending.getPlannedCarbsG()).isEqualByComparingTo("60.0");
    assertThat(pending.getPlannedFatG()).isEqualByComparingTo("15.0");
    assertThat(pending.getPlannedFibreG()).isEqualByComparingTo("8.0");
    assertThat(pending.getPlannedMicros()).isNull();
    assertThat(pending.getPlannedRecipeId()).isNull();
    assertThat(pending.isNeedsAiParse()).isFalse();
    assertThat(pending.getActualStatus()).isEqualTo(IntakeSlotStatus.PENDING);
    assertThat(day.getPlanId()).isEqualTo(planId);

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getPreviousValueJson().get("slotCount").asInt()).isEqualTo(1);
    assertThat(auditCaptor.getValue().getNewValueJson().get("slotCount").asInt()).isEqualTo(3);
  }

  @Test
  void prefillFromPlan_rePrefill_preservesDecidedSlotVerbatim() {
    // D-0008 idempotency pin: re-accepting or re-optimising must not clobber user actuals.
    UUID userId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    ObjectNode eatenMicros = objectMapper.createObjectNode().put("iron_mg", 4.2);
    IntakeSlot decided =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.BREAKFAST)
            .plannedRecipeId(UUID.randomUUID())
            .plannedCalories(400)
            .actualStatus(IntakeSlotStatus.EDITED)
            .actualCalories(450)
            .actualProteinG(BigDecimal.valueOf(28.0))
            .actualMicros(eatenMicros)
            .build();
    UUID decidedPlannedRecipe = decided.getPlannedRecipeId();
    IntakeDay day = day(userId, decided);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    service().prefillFromPlan(userId, ON_DATE, planId, NutritionTestData.defaultPlannedSlots());

    assertThat(day.getSlots()).hasSize(3);
    assertThat(day.getSlots()).contains(decided);
    // The decided slot is untouched, planned fields included: the user's record stands.
    assertThat(decided.getActualStatus()).isEqualTo(IntakeSlotStatus.EDITED);
    assertThat(decided.getActualCalories()).isEqualTo(450);
    assertThat(decided.getActualProteinG()).isEqualByComparingTo("28.0");
    assertThat(decided.getActualMicros()).isSameAs(eatenMicros);
    assertThat(decided.getPlannedCalories()).isEqualTo(400);
    assertThat(decided.getPlannedRecipeId()).isEqualTo(decidedPlannedRecipe);
    // No duplicate breakfast row was added alongside it.
    assertThat(day.getSlots()).filteredOn(s -> s.getMealSlot() == MealSlot.BREAKFAST).hasSize(1);
  }

  @Test
  void prefillFromPlan_stalePendingRemoved_staleDecidedKept() {
    UUID userId = UUID.randomUUID();
    IntakeSlot stalePending =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.LUNCH)
            .plannedCalories(600)
            .actualStatus(IntakeSlotStatus.PENDING)
            .build();
    IntakeSlot staleDecided =
        IntakeSlot.builder()
            .id(UUID.randomUUID())
            .mealSlot(MealSlot.DINNER)
            .plannedCalories(700)
            .actualStatus(IntakeSlotStatus.CONFIRMED)
            .actualCalories(700)
            .build();
    IntakeDay day = day(userId, stalePending);
    day.addSlot(staleDecided);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    // New snapshot carries breakfast only: lunch and dinner dropped from the plan.
    List<PlannedSlotInputDto> breakfastOnly =
        List.of(NutritionTestData.defaultPlannedSlots().get(0));
    service().prefillFromPlan(userId, ON_DATE, UUID.randomUUID(), breakfastOnly);

    // Stale PENDING lunch goes; the decided dinner stays with its actuals.
    assertThat(day.getSlots())
        .extracting(IntakeSlot::getMealSlot)
        .containsExactlyInAnyOrder(MealSlot.DINNER, MealSlot.BREAKFAST);
    assertThat(day.getSlots()).contains(staleDecided);
    assertThat(staleDecided.getActualCalories()).isEqualTo(700);
  }

  // ---------------- confirmFromPlan ----------------

  @Test
  void confirmFromPlan_copiesPlannedValuesIntoActuals() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    IntakeDay day = day(userId, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var dto = service().confirmFromPlan(userId, ON_DATE, MealSlot.LUNCH);

    assertThat(dto).isNotNull();
    assertThat(slot.getActualStatus()).isEqualTo(IntakeSlotStatus.CONFIRMED);
    assertThat(slot.getActualCalories()).isEqualTo(600);
    assertThat(slot.getActualProteinG()).isEqualByComparingTo("40.0");
    assertThat(slot.getActualCarbsG()).isEqualByComparingTo("70.0");
    assertThat(slot.getActualFatG()).isEqualByComparingTo("20.0");
    assertThat(slot.getActualFibreG()).isEqualByComparingTo("10.0");
    assertThat(slot.getActualMicros()).isSameAs(slot.getPlannedMicros());
    assertThat(slot.isNeedsAiParse()).isFalse();

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo(IntakeAuditAction.CONFIRM);
    assertThat(auditCaptor.getValue().getPreviousValueJson().get("status").asText())
        .isEqualTo("PENDING");
    assertThat(auditCaptor.getValue().getNewValueJson().get("status").asText())
        .isEqualTo("CONFIRMED");

    verify(divergenceDetector).detectAndPublish(eq(userId), eq(ON_DATE), any(UUID.class));
    ArgumentCaptor<IntakeLoggedEvent> eventCaptor =
        ArgumentCaptor.forClass(IntakeLoggedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().action()).isEqualTo(IntakeAuditAction.CONFIRM);
    assertThat(eventCaptor.getValue().mealSlot()).isEqualTo(MealSlot.LUNCH);
  }

  @Test
  void confirmFromPlan_alreadyConfirmed_isIdempotent() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    slot.setActualStatus(IntakeSlotStatus.CONFIRMED);
    IntakeDay day = day(userId, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var dto = service().confirmFromPlan(userId, ON_DATE, MealSlot.LUNCH);

    assertThat(dto).isNotNull();
    assertThat(dto.slots()).hasSize(1);
    verify(intakeDayRepository, never()).saveAndFlush(any());
    verifyNoInteractions(intakeAuditRepository, eventPublisher, divergenceDetector);
  }

  // ---------------- overrideIntakeFromFreeText ----------------

  @Test
  void overrideIntake_stampsOverrideStateAndZeroesActuals() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    slot.setActualStatus(IntakeSlotStatus.PENDING);
    slot.setNeedsAiParse(false);
    slot.setActualCalories(500);
    slot.setActualProteinG(BigDecimal.valueOf(30.0));
    slot.setActualCarbsG(BigDecimal.valueOf(50.0));
    slot.setActualFatG(BigDecimal.valueOf(20.0));
    slot.setActualFibreG(BigDecimal.valueOf(5.0));
    slot.setActualMicros(objectMapper.createObjectNode().put("iron_mg", 2.0));
    IntakeDay day = day(userId, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var dto =
        service().overrideIntakeFromFreeText(userId, ON_DATE, MealSlot.LUNCH, "a cheese sandwich");

    assertThat(dto).isNotNull();
    assertThat(slot.getOverrideFreeText()).isEqualTo("a cheese sandwich");
    assertThat(slot.getOverriddenAt()).isEqualTo(NOW);
    assertThat(slot.getActualStatus()).isEqualTo(IntakeSlotStatus.OVERRIDDEN);
    assertThat(slot.isNeedsAiParse()).isTrue();
    assertThat(slot.getActualCalories()).isZero();
    assertThat(slot.getActualProteinG()).isEqualByComparingTo("0");
    assertThat(slot.getActualCarbsG()).isEqualByComparingTo("0");
    assertThat(slot.getActualFatG()).isEqualByComparingTo("0");
    assertThat(slot.getActualFibreG()).isEqualByComparingTo("0");
    assertThat(slot.getActualMicros()).isNull();

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo(IntakeAuditAction.OVERRIDE);
    assertThat(auditCaptor.getValue().getNewValueJson().get("freeText").asText())
        .isEqualTo("a cheese sandwich");

    verify(divergenceDetector).detectAndPublish(eq(userId), eq(ON_DATE), any(UUID.class));
    verify(eventPublisher).publishEvent(any(IntakeLoggedEvent.class));
  }

  // ---------------- editIntakeManually ----------------

  @Test
  void editIntake_writesEveryMacroAndMicrosDocument() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    slot.setActualStatus(IntakeSlotStatus.PENDING);
    slot.setNeedsAiParse(false);
    IntakeDay day = day(userId, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));
    var micros = objectMapper.createObjectNode().put("saturated_fat_g", 4.0);

    var dto =
        service()
            .editIntakeManually(
                userId,
                ON_DATE,
                MealSlot.LUNCH,
                new IntakeEntryDto(
                    420,
                    BigDecimal.valueOf(26.0),
                    BigDecimal.valueOf(52.0),
                    BigDecimal.valueOf(12.0),
                    BigDecimal.valueOf(6.0),
                    micros));

    assertThat(dto).isNotNull();
    assertThat(slot.getActualProteinG()).isEqualByComparingTo("26.0");
    assertThat(slot.getActualCarbsG()).isEqualByComparingTo("52.0");
    assertThat(slot.getActualFatG()).isEqualByComparingTo("12.0");
    assertThat(slot.getActualFibreG()).isEqualByComparingTo("6.0");
    assertThat(slot.getActualMicros()).isSameAs(micros);
    verify(divergenceDetector).detectAndPublish(eq(userId), eq(ON_DATE), any(UUID.class));
  }

  // ---------------- skipMeal ----------------

  @Test
  void skipMeal_zeroesActualsAndMarksSkipped() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = plannedLunchSlot();
    slot.setActualCalories(500);
    slot.setActualProteinG(BigDecimal.valueOf(30.0));
    slot.setActualCarbsG(BigDecimal.valueOf(50.0));
    slot.setActualFatG(BigDecimal.valueOf(20.0));
    slot.setActualFibreG(BigDecimal.valueOf(5.0));
    slot.setActualMicros(objectMapper.createObjectNode().put("iron_mg", 2.0));
    IntakeDay day = day(userId, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var dto = service().skipMeal(userId, ON_DATE, MealSlot.LUNCH);

    assertThat(dto).isNotNull();
    assertThat(slot.getActualStatus()).isEqualTo(IntakeSlotStatus.SKIPPED);
    assertThat(slot.getActualCalories()).isZero();
    assertThat(slot.getActualProteinG()).isEqualByComparingTo("0");
    assertThat(slot.getActualCarbsG()).isEqualByComparingTo("0");
    assertThat(slot.getActualFatG()).isEqualByComparingTo("0");
    assertThat(slot.getActualFibreG()).isEqualByComparingTo("0");
    assertThat(slot.getActualMicros()).isNull();
    assertThat(slot.isNeedsAiParse()).isFalse();

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo(IntakeAuditAction.SKIP);
    assertThat(auditCaptor.getValue().getPreviousValueJson().get("status").asText())
        .isEqualTo("PENDING");

    verify(divergenceDetector).detectAndPublish(eq(userId), eq(ON_DATE), any(UUID.class));
    verify(eventPublisher).publishEvent(any(IntakeLoggedEvent.class));
  }

  // ---------------- logSnack / removeSnack ----------------

  @Test
  void logSnack_attachesSnackToDay_auditsAndPublishes() {
    UUID userId = UUID.randomUUID();
    IntakeDay day = day(userId, null);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));
    LogSnackRequest request =
        new LogSnackRequest(
            "protein bar",
            null,
            BigDecimal.valueOf(60),
            240,
            BigDecimal.valueOf(20),
            BigDecimal.valueOf(20),
            BigDecimal.valueOf(8),
            BigDecimal.valueOf(2),
            null,
            IntakeSource.MANUAL,
            null);

    var dto = service().logSnack(userId, ON_DATE, request);

    assertThat(dto).isNotNull();
    assertThat(dto.snacks()).hasSize(1);
    assertThat(day.getSnacks()).hasSize(1);
    IntakeSnack persisted = day.getSnacks().get(0);
    assertThat(persisted.getFreeText()).isEqualTo("protein bar");
    assertThat(persisted.getCalories()).isEqualTo(240);
    assertThat(persisted.getLoggedAt()).isEqualTo(NOW);

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo(IntakeAuditAction.SNACK_ADD);
    assertThat(auditCaptor.getValue().getSnackId()).isEqualTo(persisted.getId());
    assertThat(auditCaptor.getValue().getNewValueJson().get("calories").asInt()).isEqualTo(240);

    ArgumentCaptor<IntakeLoggedEvent> eventCaptor =
        ArgumentCaptor.forClass(IntakeLoggedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().action()).isEqualTo(IntakeAuditAction.SNACK_ADD);
    assertThat(eventCaptor.getValue().snackId()).isEqualTo(persisted.getId());
  }

  @Test
  void removeSnack_whenDayMissing_throwsNotFound() {
    UUID userId = UUID.randomUUID();
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().removeSnack(userId, ON_DATE, UUID.randomUUID()))
        .isInstanceOf(IntakeSnackNotFoundException.class);
  }

  @Test
  void removeSnack_whenSnackIdUnknown_throwsAndLeavesDayIntact() {
    UUID userId = UUID.randomUUID();
    IntakeDay day = day(userId, null);
    day.addSnack(snack("almonds", 180));
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    assertThatThrownBy(() -> service().removeSnack(userId, ON_DATE, UUID.randomUUID()))
        .isInstanceOf(IntakeSnackNotFoundException.class);

    assertThat(day.getSnacks()).hasSize(1);
    verify(intakeDayRepository, never()).saveAndFlush(any());
  }

  @Test
  void removeSnack_removesOnlyTheTargetSnack() {
    UUID userId = UUID.randomUUID();
    IntakeDay day = day(userId, null);
    IntakeSnack almonds = snack("almonds", 180);
    IntakeSnack yoghurt = snack("yoghurt", 120);
    day.addSnack(almonds);
    day.addSnack(yoghurt);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, ON_DATE)).thenReturn(Optional.of(day));

    var dto = service().removeSnack(userId, ON_DATE, almonds.getId());

    assertThat(dto).isNotNull();
    assertThat(dto.snacks()).hasSize(1);
    assertThat(day.getSnacks()).containsExactly(yoghurt);

    ArgumentCaptor<IntakeAuditLog> auditCaptor = ArgumentCaptor.forClass(IntakeAuditLog.class);
    verify(intakeAuditRepository).save(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getAction()).isEqualTo(IntakeAuditAction.SNACK_REMOVE);
    assertThat(auditCaptor.getValue().getPreviousValueJson().get("freeText").asText())
        .isEqualTo("almonds");

    ArgumentCaptor<IntakeLoggedEvent> eventCaptor =
        ArgumentCaptor.forClass(IntakeLoggedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().snackId()).isEqualTo(almonds.getId());
  }

  // ---------------- Daily activity ----------------

  @Test
  void getDailyActivity_whenPresent_mapsRow() {
    UUID userId = UUID.randomUUID();
    DailyActivityLog row =
        DailyActivityLog.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(ON_DATE)
            .activityLevel(ActivityLevel.TRAINING_DAY)
            .notes("leg day")
            .build();
    when(dailyActivityLogRepository.findByUserIdAndOnDate(userId, ON_DATE))
        .thenReturn(Optional.of(row));

    var dto = service().getDailyActivity(userId, ON_DATE);

    assertThat(dto).isPresent();
    assertThat(dto.get().activityLevel()).isEqualTo(ActivityLevel.TRAINING_DAY);
  }

  @Test
  void getDailyActivityRange_validatesRangeAndMapsRows() {
    UUID userId = UUID.randomUUID();
    assertThatThrownBy(() -> service().getDailyActivityRange(userId, null, ON_DATE))
        .isInstanceOf(InvalidIntakeRangeException.class);

    DailyActivityLog row =
        DailyActivityLog.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(ON_DATE)
            .activityLevel(ActivityLevel.REST_DAY)
            .build();
    when(dailyActivityLogRepository.findByUserIdAndOnDateBetween(userId, ON_DATE, ON_DATE))
        .thenReturn(List.of(row));

    assertThat(service().getDailyActivityRange(userId, ON_DATE, ON_DATE)).hasSize(1);
  }

  @Test
  void upsertDailyActivity_whenAbsent_createsRow() {
    UUID userId = UUID.randomUUID();
    when(dailyActivityLogRepository.findByUserIdAndOnDate(userId, ON_DATE))
        .thenReturn(Optional.empty());
    when(dailyActivityLogRepository.saveAndFlush(any(DailyActivityLog.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var dto = service().upsertDailyActivity(userId, ON_DATE, ActivityLevel.REST_DAY, "rest");

    assertThat(dto).isNotNull();
    assertThat(dto.activityLevel()).isEqualTo(ActivityLevel.REST_DAY);
    assertThat(dto.notes()).isEqualTo("rest");
    ArgumentCaptor<DailyActivityLog> captor = ArgumentCaptor.forClass(DailyActivityLog.class);
    verify(dailyActivityLogRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    assertThat(captor.getValue().getOnDate()).isEqualTo(ON_DATE);
  }

  @Test
  void upsertDailyActivity_whenPresent_overwritesLevelAndNotes() {
    UUID userId = UUID.randomUUID();
    DailyActivityLog existing =
        DailyActivityLog.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(ON_DATE)
            .activityLevel(ActivityLevel.TRAINING_DAY)
            .notes("old")
            .build();
    when(dailyActivityLogRepository.findByUserIdAndOnDate(userId, ON_DATE))
        .thenReturn(Optional.of(existing));
    when(dailyActivityLogRepository.saveAndFlush(any(DailyActivityLog.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    service().upsertDailyActivity(userId, ON_DATE, ActivityLevel.REST_DAY, "new");

    assertThat(existing.getActivityLevel()).isEqualTo(ActivityLevel.REST_DAY);
    assertThat(existing.getNotes()).isEqualTo("new");
  }

  // ---------------- Journal ----------------

  private static FoodMoodJournalEntry journalEntry(UUID userId, Instant loggedAt) {
    return FoodMoodJournalEntry.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .onDate(ON_DATE)
        .mealSlot(MealSlot.LUNCH)
        .journalEntry("felt good")
        .loggedAt(loggedAt)
        .optimisticVersion(0L)
        .build();
  }

  @Test
  void getJournalEntriesForDay_mapsEntries() {
    UUID userId = UUID.randomUUID();
    when(journalRepository.findByUserIdAndOnDateOrderByLoggedAtAsc(userId, ON_DATE))
        .thenReturn(List.of(journalEntry(userId, NOW)));

    var entries = service().getJournalEntriesForDay(userId, ON_DATE);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).journalEntry()).isEqualTo("felt good");
  }

  @Test
  void getRecentJournalEntries_mapsPage() {
    UUID userId = UUID.randomUUID();
    when(journalRepository.findByUserIdOrderByLoggedAtDesc(eq(userId), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(journalEntry(userId, NOW))));

    var page = service().getRecentJournalEntries(userId, PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
  }

  @Test
  void getJournalEntriesForFeedbackContext_returnsRecentEntries() {
    UUID userId = UUID.randomUUID();
    when(journalRepository.findTop20ByUserIdOrderByLoggedAtDesc(userId))
        .thenReturn(List.of(journalEntry(userId, NOW)));

    assertThat(service().getJournalEntriesForFeedbackContext(userId)).hasSize(1);
  }

  @Test
  void updateJournalEntry_overwritesSlotTextAndLoggedAt() {
    UUID userId = UUID.randomUUID();
    FoodMoodJournalEntry existing = journalEntry(userId, Instant.parse("2026-05-09T08:00:00Z"));
    when(journalRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
    when(journalRepository.saveAndFlush(any(FoodMoodJournalEntry.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var dto =
        service()
            .updateJournalEntry(
                userId,
                existing.getId(),
                NutritionTestData.journalRequest(ON_DATE, MealSlot.DINNER, "updated text", 0L));

    assertThat(dto).isNotNull();
    assertThat(existing.getMealSlot()).isEqualTo(MealSlot.DINNER);
    assertThat(existing.getJournalEntry()).isEqualTo("updated text");
    assertThat(existing.getLoggedAt()).isEqualTo(Instant.parse("2026-05-09T12:30:00Z"));
  }

  @Test
  void deleteJournalEntry_ownedByAnotherUser_throwsNotFound() {
    FoodMoodJournalEntry foreign = journalEntry(UUID.randomUUID(), NOW);
    when(journalRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service().deleteJournalEntry(UUID.randomUUID(), foreign.getId()))
        .isInstanceOf(JournalEntryNotFoundException.class);

    verify(journalRepository, never()).delete(any());
  }

  // ---------------- Ingredient lookup ----------------

  @Test
  void lookupIngredient_normalisesTermBeforeLookup() {
    IngredientMapping row =
        mapping("chicken breast", NutritionTestData.defaultNutritionDocument(), BigDecimal.ONE);
    when(ingredientMappingRepository.findBySearchTerm("chicken breast"))
        .thenReturn(Optional.of(row));

    var dto = service().lookupIngredient("  Chicken   Breast ");

    assertThat(dto).isPresent();
    assertThat(dto.get().searchTerm()).isEqualTo("chicken breast");
  }

  @Test
  void lookupIngredient_blankTerm_shortCircuitsToEmpty() {
    assertThat(service().lookupIngredient("   ")).isEmpty();
    assertThat(service().lookupIngredient(null)).isEmpty();
    verifyNoInteractions(ingredientMappingRepository);
  }

  @Test
  void lookupIngredients_filtersBlanksAndNormalises() {
    assertThat(service().lookupIngredients(null)).isEmpty();
    assertThat(service().lookupIngredients(List.of("   "))).isEmpty();
    verifyNoInteractions(ingredientMappingRepository);

    IngredientMapping row =
        mapping("beef mince", NutritionTestData.defaultNutritionDocument(), BigDecimal.ONE);
    when(ingredientMappingRepository.findBySearchTermIn(List.of("beef mince")))
        .thenReturn(List.of(row));

    var hits = service().lookupIngredients(List.of(" Beef  Mince "));

    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).searchTerm()).isEqualTo("beef mince");
  }

  @Test
  void searchIngredientsForUi_defaultsAndClampsPageSize() {
    IngredientMapping row =
        mapping("oats", NutritionTestData.defaultNutritionDocument(), BigDecimal.ONE);
    when(ingredientMappingRepository.searchByTerm(eq("oat"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));

    var result = service().searchIngredientsForUi(new IngredientLookupRequest("oat", null));
    assertThat(result).isNotNull();
    assertThat(result.hits()).hasSize(1);

    service().searchIngredientsForUi(new IngredientLookupRequest("oat", 25));

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(ingredientMappingRepository, times(2)).searchByTerm(eq("oat"), pageableCaptor.capture());
    assertThat(pageableCaptor.getAllValues().get(0).getPageSize()).isEqualTo(10);
    assertThat(pageableCaptor.getAllValues().get(1).getPageSize()).isEqualTo(20);
  }

  @Test
  void getMappingsNeedingReview_mapsPage() {
    IngredientMapping row =
        mapping("mystery paste", NutritionTestData.defaultNutritionDocument(), BigDecimal.ONE);
    when(ingredientMappingRepository.findByNeedsReviewTrueOrderByUpdatedAtDesc(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(row)));

    var page = service().getMappingsNeedingReview(PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).searchTerm()).isEqualTo("mystery paste");
  }

  // ---------------- correctIngredientMapping ----------------

  @Test
  void correctIngredientMapping_unknownTerm_throwsNotFound() {
    when(ingredientMappingRepository.findBySearchTerm("nope")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .correctIngredientMapping(
                        "nope",
                        NutritionTestData.defaultNutritionDocument(),
                        0L,
                        UUID.randomUUID()))
        .isInstanceOf(IngredientMappingNotFoundException.class);
  }

  @Test
  void correctIngredientMapping_staleVersion_throwsOptimisticLockFailure() {
    IngredientMapping row =
        mapping("chicken breast", NutritionTestData.defaultNutritionDocument(), BigDecimal.ONE);
    row.setNeedsReview(true);
    when(ingredientMappingRepository.findBySearchTerm("chicken breast"))
        .thenReturn(Optional.of(row));

    assertThatThrownBy(
            () ->
                service()
                    .correctIngredientMapping(
                        "chicken breast",
                        NutritionTestData.defaultNutritionDocument(),
                        3L,
                        UUID.randomUUID()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(row.isNeedsReview()).isTrue();
  }

  @Test
  void correctIngredientMapping_appliesManualOverrideAndPublishes() {
    UUID actor = UUID.randomUUID();
    IngredientMapping row =
        mapping(
            "chicken breast", NutritionTestData.defaultNutritionDocument(), new BigDecimal("0.40"));
    row.setNeedsReview(true);
    when(ingredientMappingRepository.findBySearchTerm("chicken breast"))
        .thenReturn(Optional.of(row));
    when(ingredientMappingRepository.saveAndFlush(any(IngredientMapping.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    IngredientNutritionDocument override = NutritionTestData.defaultNutritionDocument();

    var dto = service().correctIngredientMapping("Chicken  Breast", override, 0L, actor);

    assertThat(dto).isNotNull();
    assertThat(dto.needsReview()).isFalse();
    assertThat(row.getNutritionPer100g()).isSameAs(override);
    assertThat(row.getSource()).isEqualTo(IngredientMappingSource.MANUAL);
    assertThat(row.getConfidence()).isEqualByComparingTo("1.0");
    assertThat(row.isNeedsReview()).isFalse();
    assertThat(row.getLastVerifiedAt()).isEqualTo(NOW);

    ArgumentCaptor<IngredientMappingCorrectedEvent> eventCaptor =
        ArgumentCaptor.forClass(IngredientMappingCorrectedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().searchTerm()).isEqualTo("chicken breast");
    assertThat(eventCaptor.getValue().actorUserId()).isEqualTo(actor);
    assertThat(eventCaptor.getValue().occurredAt()).isEqualTo(NOW);
  }

  // ---------------- Health directives: query ----------------

  @Test
  void getDirectives_routesByFilterPresence() {
    UUID userId = UUID.randomUUID();
    HealthDirective directive = pendingDirective(userId, adjustProteinFloor(130));
    when(healthDirectiveRepository.findByUserIdOrderByReceivedAtDesc(
            eq(userId), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(directive)));
    when(healthDirectiveRepository.findByUserIdAndStatusOrderByReceivedAtDesc(
            eq(userId), eq(DirectiveStatus.PENDING_REVIEW), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(directive)));

    assertThat(service().getDirectives(userId, null, PageRequest.of(0, 10)).getContent())
        .hasSize(1);
    assertThat(
            service()
                .getDirectives(userId, DirectiveStatus.PENDING_REVIEW, PageRequest.of(0, 10))
                .getContent())
        .hasSize(1);
  }

  @Test
  void getDirective_returnsOnlyOwnedRows() {
    UUID owner = UUID.randomUUID();
    HealthDirective directive = pendingDirective(owner, adjustProteinFloor(130));
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));

    assertThat(service().getDirective(owner, directive.getId())).isPresent();
    assertThat(service().getDirective(UUID.randomUUID(), directive.getId())).isEmpty();
  }

  // ---------------- Health directives: inbound ----------------

  @Test
  void receiveInboundDirective_duplicateDelivery_throwsConflict() {
    UUID userId = UUID.randomUUID();
    HealthDirective existing = pendingDirective(userId, adjustProteinFloor(130));
    when(healthDirectiveRepository.findBySourcePlatformAndExternalDirectiveId(
            "apple-health", "ext-1"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service()
                    .receiveInboundDirective(
                        userId, NutritionTestData.defaultInboundDirectiveRequest(userId, "ext-1")))
        .isInstanceOf(DuplicateHealthDirectiveException.class);

    verify(healthDirectiveRepository, never()).saveAndFlush(any());
  }

  @Test
  void receiveInboundDirective_temporaryWithoutExpiry_isRejected() {
    UUID userId = UUID.randomUUID();
    var request =
        NutritionTestData.inboundDirectiveRequest(
            userId,
            "ext-2",
            "apple-health",
            DirectiveType.TARGET_ADJUSTMENT,
            adjustProteinFloor(130),
            "nutrition_model",
            "protein_floor_g",
            true,
            null);

    assertThatThrownBy(() -> service().receiveInboundDirective(userId, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void receiveInboundDirective_permanentWithoutExpiry_isAccepted() {
    UUID userId = UUID.randomUUID();
    when(healthDirectiveRepository.saveAndFlush(any(HealthDirective.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    var request =
        NutritionTestData.inboundDirectiveRequest(
            userId,
            "ext-3",
            "apple-health",
            DirectiveType.TARGET_ADJUSTMENT,
            adjustProteinFloor(130),
            "nutrition_model",
            "protein_floor_g",
            false,
            null);

    assertThat(service().receiveInboundDirective(userId, request)).isNotNull();
  }

  @Test
  void receiveInboundDirective_persistsPendingRowAndPublishes() {
    UUID userId = UUID.randomUUID();
    when(healthDirectiveRepository.saveAndFlush(any(HealthDirective.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var dto =
        service()
            .receiveInboundDirective(
                userId, NutritionTestData.defaultInboundDirectiveRequest(userId, "ext-9"));

    assertThat(dto).isNotNull();
    assertThat(dto.status()).isEqualTo(DirectiveStatus.PENDING_REVIEW);

    ArgumentCaptor<HealthDirective> captor = ArgumentCaptor.forClass(HealthDirective.class);
    verify(healthDirectiveRepository).saveAndFlush(captor.capture());
    HealthDirective saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getExternalDirectiveId()).isEqualTo("ext-9");
    assertThat(saved.getSourcePlatform()).isEqualTo("apple-health");
    assertThat(saved.getStatus()).isEqualTo(DirectiveStatus.PENDING_REVIEW);
    assertThat(saved.getReceivedAt()).isEqualTo(NOW);
    assertThat(saved.isTemporary()).isTrue();
    assertThat(saved.getMapsToModel()).isEqualTo("nutrition_model");

    ArgumentCaptor<HealthDirectiveReceivedEvent> eventCaptor =
        ArgumentCaptor.forClass(HealthDirectiveReceivedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().directiveId()).isEqualTo(saved.getId());
    assertThat(eventCaptor.getValue().sourcePlatform()).isEqualTo("apple-health");
    assertThat(eventCaptor.getValue().occurredAt()).isEqualTo(NOW);
  }

  // ---------------- Health directives: accept ----------------

  @Test
  void acceptDirective_gatePasses_appliesAndMarksAccepted() {
    UUID userId = UUID.randomUUID();
    DirectiveInstructionDocument instruction = adjustProteinFloor(130);
    HealthDirective directive = pendingDirective(userId, instruction);
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));
    when(healthDirectiveRepository.saveAndFlush(any(HealthDirective.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var dto =
        service()
            .acceptHealthDirective(
                userId, directive.getId(), NutritionTestData.acceptRequest(null, 0L));

    assertThat(dto).isNotNull();
    assertThat(dto.status()).isEqualTo(DirectiveStatus.ACCEPTED);
    assertThat(directive.getSafetyGateVerdict()).isEqualTo(SafetyGateVerdict.PASSED);
    assertThat(directive.getSafetyGateFindings()).isNotNull().isEmpty();
    assertThat(directive.getStatus()).isEqualTo(DirectiveStatus.ACCEPTED);
    assertThat(directive.getDecidedAt()).isEqualTo(NOW);
    assertThat(directive.getDecidedByUserId()).isEqualTo(userId);
    assertThat(directive.getUserModificationJson()).isNull();

    verify(directiveApplier).apply(same(directive), same(instruction), eq(userId));

    ArgumentCaptor<HealthDirectiveAcceptedEvent> eventCaptor =
        ArgumentCaptor.forClass(HealthDirectiveAcceptedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().directiveId()).isEqualTo(directive.getId());
    assertThat(eventCaptor.getValue().userModified()).isFalse();
    assertThat(eventCaptor.getValue().occurredAt()).isEqualTo(NOW);
  }

  @Test
  void acceptDirective_userModification_replacesInstructionAndFlagsEvent() {
    UUID userId = UUID.randomUUID();
    HealthDirective directive = pendingDirective(userId, adjustProteinFloor(130));
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));
    when(healthDirectiveRepository.saveAndFlush(any(HealthDirective.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    DirectiveInstructionDocument modification = adjustProteinFloor(100);

    service()
        .acceptHealthDirective(
            userId, directive.getId(), NutritionTestData.acceptRequest(modification, 0L));

    assertThat(directive.getUserModificationJson()).isSameAs(modification);
    verify(directiveApplier).apply(same(directive), same(modification), eq(userId));
    ArgumentCaptor<HealthDirectiveAcceptedEvent> eventCaptor =
        ArgumentCaptor.forClass(HealthDirectiveAcceptedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().userModified()).isTrue();
  }

  @Test
  void acceptDirective_gateBlocked_persistsVerdictAndAppliesNothing() {
    UUID userId = UUID.randomUUID();
    // 200 g floor against a 120 g daily target breaches the 1.2x raise bound.
    HealthDirective directive = pendingDirective(userId, adjustProteinFloor(200));
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));
    when(healthDirectiveRepository.saveAndFlush(any(HealthDirective.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () ->
                service()
                    .acceptHealthDirective(
                        userId, directive.getId(), NutritionTestData.acceptRequest(null, 0L)))
        .isInstanceOf(HealthDirectiveSafetyGateBlockedException.class);

    assertThat(directive.getSafetyGateVerdict()).isEqualTo(SafetyGateVerdict.BLOCKED);
    assertThat(directive.getSafetyGateFindings()).hasSize(1);
    assertThat(directive.getStatus()).isEqualTo(DirectiveStatus.PENDING_REVIEW);
    verify(healthDirectiveRepository).saveAndFlush(directive);
    verifyNoInteractions(directiveApplier);
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void acceptDirective_alreadyDecided_throws() {
    UUID userId = UUID.randomUUID();
    HealthDirective directive = pendingDirective(userId, adjustProteinFloor(130));
    directive.setStatus(DirectiveStatus.ACCEPTED);
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));

    assertThatThrownBy(
            () ->
                service()
                    .acceptHealthDirective(
                        userId, directive.getId(), NutritionTestData.acceptRequest(null, 0L)))
        .isInstanceOf(HealthDirectiveAlreadyDecidedException.class);
  }

  @Test
  void acceptDirective_staleVersion_throwsOptimisticLockFailure() {
    UUID userId = UUID.randomUUID();
    HealthDirective directive = pendingDirective(userId, adjustProteinFloor(130));
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));

    assertThatThrownBy(
            () ->
                service()
                    .acceptHealthDirective(
                        userId, directive.getId(), NutritionTestData.acceptRequest(null, 3L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void acceptDirective_unknownOrForeignId_throwsNotFound() {
    UUID directiveId = UUID.randomUUID();
    when(healthDirectiveRepository.findById(directiveId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service()
                    .acceptHealthDirective(
                        UUID.randomUUID(), directiveId, NutritionTestData.acceptRequest(null, 0L)))
        .isInstanceOf(HealthDirectiveNotFoundException.class);

    HealthDirective foreign = pendingDirective(UUID.randomUUID(), adjustProteinFloor(130));
    when(healthDirectiveRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
    assertThatThrownBy(
            () ->
                service()
                    .acceptHealthDirective(
                        UUID.randomUUID(),
                        foreign.getId(),
                        NutritionTestData.acceptRequest(null, 0L)))
        .isInstanceOf(HealthDirectiveNotFoundException.class);
  }

  // ---------------- Health directives: reject / sweep ----------------

  @Test
  void rejectDirective_recordsDecision() {
    UUID userId = UUID.randomUUID();
    HealthDirective directive = pendingDirective(userId, adjustProteinFloor(130));
    when(healthDirectiveRepository.findById(directive.getId())).thenReturn(Optional.of(directive));
    when(healthDirectiveRepository.saveAndFlush(any(HealthDirective.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    var dto =
        service()
            .rejectHealthDirective(
                userId,
                directive.getId(),
                NutritionTestData.rejectRequest("prefer to keep current targets", 0L));

    assertThat(dto).isNotNull();
    assertThat(dto.rejectionReason()).isEqualTo("prefer to keep current targets");
    assertThat(directive.getStatus()).isEqualTo(DirectiveStatus.REJECTED);
    assertThat(directive.getDecidedAt()).isEqualTo(NOW);
    assertThat(directive.getDecidedByUserId()).isEqualTo(userId);
    assertThat(directive.getRejectionReason()).isEqualTo("prefer to keep current targets");
  }

  @Test
  void rejectDirective_guardsDecidedStateAndVersion() {
    UUID userId = UUID.randomUUID();
    HealthDirective decided = pendingDirective(userId, adjustProteinFloor(130));
    decided.setStatus(DirectiveStatus.REJECTED);
    when(healthDirectiveRepository.findById(decided.getId())).thenReturn(Optional.of(decided));
    assertThatThrownBy(
            () ->
                service()
                    .rejectHealthDirective(
                        userId, decided.getId(), NutritionTestData.rejectRequest("again", 0L)))
        .isInstanceOf(HealthDirectiveAlreadyDecidedException.class);

    HealthDirective stale = pendingDirective(userId, adjustProteinFloor(130));
    when(healthDirectiveRepository.findById(stale.getId())).thenReturn(Optional.of(stale));
    assertThatThrownBy(
            () ->
                service()
                    .rejectHealthDirective(
                        userId, stale.getId(), NutritionTestData.rejectRequest("stale", 5L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void sweepExpiredDirectives_revertsAndExpiresEachRow() {
    UUID userId = UUID.randomUUID();
    HealthDirective first = pendingDirective(userId, adjustProteinFloor(130));
    first.setStatus(DirectiveStatus.ACCEPTED);
    HealthDirective second = pendingDirective(userId, adjustProteinFloor(130));
    second.setStatus(DirectiveStatus.ACCEPTED);
    when(healthDirectiveRepository.findByStatusAndAutoExpiresAtBefore(
            DirectiveStatus.ACCEPTED, NOW))
        .thenReturn(List.of(first, second));

    int swept = service().sweepExpiredDirectives();

    assertThat(swept).isEqualTo(2);
    assertThat(first.getStatus()).isEqualTo(DirectiveStatus.EXPIRED);
    assertThat(second.getStatus()).isEqualTo(DirectiveStatus.EXPIRED);
    verify(directiveApplier, times(2)).revertExpired(any(HealthDirective.class));
    verify(healthDirectiveRepository).saveAll(List.of(first, second));
  }

  // ---------------- Recipe nutrition calculation ----------------

  private static IngredientNutritionDocument chickenDoc() {
    return new IngredientNutritionDocument(
        165,
        BigDecimal.valueOf(31.0),
        BigDecimal.valueOf(0.0),
        BigDecimal.valueOf(3.6),
        BigDecimal.valueOf(0.0),
        BigDecimal.valueOf(1.0),
        BigDecimal.valueOf(0.0),
        Map.of("iron_mg", BigDecimal.valueOf(1.0)),
        Map.of());
  }

  private static IngredientNutritionDocument riceDoc() {
    return new IngredientNutritionDocument(
        110,
        BigDecimal.valueOf(2.5),
        BigDecimal.valueOf(23.0),
        BigDecimal.valueOf(0.9),
        BigDecimal.valueOf(1.8),
        BigDecimal.valueOf(0.2),
        BigDecimal.valueOf(0.1),
        Map.of("iron_mg", BigDecimal.valueOf(0.4)),
        Map.of());
  }

  @Test
  void calculateRecipeNutrition_allLinesMapped_sumsAndDividesByServings() {
    IngredientMapping chicken = mapping("chicken breast", chickenDoc(), new BigDecimal("0.95"));
    IngredientMapping rice = mapping("brown rice", riceDoc(), new BigDecimal("0.90"));
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(chicken, rice));
    UUID recipeId = UUID.randomUUID();

    // First line resolves by its explicit key; the second by its normalised name.
    var result =
        service()
            .calculateRecipeNutrition(
                new CalculateRecipeNutritionRequest(
                    recipeId,
                    List.of(
                        NutritionTestData.ingredientLine(
                            "Skinless Fillet", "chicken breast", BigDecimal.valueOf(200)),
                        NutritionTestData.ingredientLine(
                            "Brown  Rice", null, BigDecimal.valueOf(50))),
                    2));

    assertThat(result.nutritionStatus()).isEqualTo("calculated");
    assertThat(result.unmapped()).isEmpty();
    assertThat(result.caloriesPerServing()).isEqualTo(193);
    assertThat(result.proteinPerServingG()).isEqualByComparingTo("31.63");
    assertThat(result.carbsPerServingG()).isEqualByComparingTo("5.75");
    assertThat(result.fatPerServingG()).isEqualByComparingTo("3.83");
    assertThat(result.fibrePerServingG()).isEqualByComparingTo("0.45");
    assertThat(result.microsPerServing().get("iron_mg")).isEqualByComparingTo("1.10");
  }

  @Test
  void calculateRecipeNutrition_unknownIngredient_degradesToPartial() {
    IngredientMapping chicken = mapping("chicken breast", chickenDoc(), new BigDecimal("0.95"));
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(chicken));

    var result =
        service()
            .calculateRecipeNutrition(
                new CalculateRecipeNutritionRequest(
                    UUID.randomUUID(),
                    List.of(
                        NutritionTestData.ingredientLine(
                            "chicken", "chicken breast", BigDecimal.valueOf(100)),
                        NutritionTestData.ingredientLine(
                            "dragonfruit", "dragonfruit", BigDecimal.valueOf(50))),
                    1));

    assertThat(result.nutritionStatus()).isEqualTo("partial");
    assertThat(result.caloriesPerServing()).isEqualTo(165);
    assertThat(result.unmapped()).hasSize(1);
    assertThat(result.unmapped().get(0).name()).isEqualTo("dragonfruit");
    assertThat(result.unmapped().get(0).reason()).isEqualTo("not-in-cache");
  }

  @Test
  void calculateRecipeNutrition_gramsUnknown_reportsLineWithMappingConfidence() {
    IngredientMapping chicken = mapping("chicken breast", chickenDoc(), new BigDecimal("0.95"));
    IngredientMapping oil = mapping("olive oil", riceDoc(), null);
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(chicken, oil));

    var result =
        service()
            .calculateRecipeNutrition(
                new CalculateRecipeNutritionRequest(
                    UUID.randomUUID(),
                    List.of(
                        NutritionTestData.ingredientLine(
                            "chicken", "chicken breast", BigDecimal.valueOf(100)),
                        NutritionTestData.ingredientLine("one fillet", "chicken breast", null),
                        NutritionTestData.ingredientLine("a glug", "olive oil", null)),
                    1));

    assertThat(result.nutritionStatus()).isEqualTo("partial");
    assertThat(result.unmapped()).hasSize(2);
    var byName =
        result.unmapped().stream()
            .collect(java.util.stream.Collectors.toMap(u -> u.name(), u -> u));
    assertThat(byName.get("one fillet").reason()).isEqualTo("grams-unknown");
    assertThat(byName.get("one fillet").confidence()).isEqualByComparingTo("0.95");
    assertThat(byName.get("a glug").confidence()).isEqualByComparingTo("0");
  }

  @Test
  void calculateRecipeNutrition_nothingContributesGrams_staysPending() {
    IngredientMapping chicken = mapping("chicken breast", chickenDoc(), new BigDecimal("0.95"));
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(chicken));

    var result =
        service()
            .calculateRecipeNutrition(
                new CalculateRecipeNutritionRequest(
                    UUID.randomUUID(),
                    List.of(NutritionTestData.ingredientLine("one fillet", "chicken breast", null)),
                    1));

    assertThat(result.nutritionStatus()).isEqualTo("pending");
    assertThat(result.caloriesPerServing()).isZero();
    assertThat(result.unmapped()).hasSize(1);
  }

  // ---------------- Floor gate ----------------

  @Test
  void evaluate_flagsOnlyFloorsActuallyBreached() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .withCarbsFloor(BigDecimal.valueOf(150.0))
            .withFatFloor(BigDecimal.valueOf(50.0))
            .withFibreFloor(BigDecimal.valueOf(20.0))
            .withMicroHardFloor("iron_mg", BigDecimal.valueOf(18.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    LocalDate d1 = LocalDate.of(2026, 5, 4);
    LocalDate d2 = d1.plusDays(1);
    // Day 1 sits exactly on the protein and iron floors; carbs and fat fall short.
    var day1 =
        new CandidateDailyRollupDto(
            d1,
            ActivityLevel.LIGHT_ACTIVITY,
            2000,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(140.0),
            BigDecimal.valueOf(45.0),
            BigDecimal.valueOf(25.0),
            Map.of("iron_mg", BigDecimal.valueOf(18.0)));
    // Day 2 carries no micros document at all, so iron reads as zero.
    var day2 =
        new CandidateDailyRollupDto(
            d2,
            ActivityLevel.LIGHT_ACTIVITY,
            2000,
            BigDecimal.valueOf(120.0),
            BigDecimal.valueOf(160.0),
            BigDecimal.valueOf(60.0),
            BigDecimal.valueOf(25.0),
            null);

    var result =
        service().evaluate(userId, new CandidatePlanRollupDto(d1, d2, List.of(day1, day2)));

    assertThat(result.passed()).isFalse();
    assertThat(result.violations())
        .extracting(FloorViolationDto::macroOrMicro)
        .containsExactlyInAnyOrder("carbs", "fat", "iron_mg");
    FloorViolationDto iron =
        result.violations().stream()
            .filter(v -> "iron_mg".equals(v.macroOrMicro()))
            .findFirst()
            .orElseThrow();
    assertThat(iron.date()).isEqualTo(d2);
    assertThat(iron.actual()).isEqualByComparingTo("0");
  }

  @Test
  void evaluateForHousehold_evaluatesEveryMember() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    LocalDate d1 = LocalDate.of(2026, 5, 4);
    var rollup = new CandidatePlanRollupDto(d1, d1, List.of(NutritionTestData.dailyRollup(d1)));

    var results = service().evaluateForHousehold(List.of(first, second), rollup);

    assertThat(results).hasSize(2);
    assertThat(results.keySet()).containsExactly(first, second);
    assertThat(results.get(first).passed()).isTrue();
    assertThat(results.get(second).passed()).isTrue();
  }
}
