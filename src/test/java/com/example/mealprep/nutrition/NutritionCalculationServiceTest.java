package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.DailyActivityLogRepository;
import com.example.mealprep.nutrition.domain.repository.FoodMoodJournalRepository;
import com.example.mealprep.nutrition.domain.repository.HealthDirectiveRepository;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeAuditRepository;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsAuditRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveApplier;
import com.example.mealprep.nutrition.domain.service.internal.DirectiveSafetyGate;
import com.example.mealprep.nutrition.domain.service.internal.IntakeKeyNormaliser;
import com.example.mealprep.nutrition.domain.service.internal.NutritionServiceImpl;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Pure-arithmetic unit test for the {@code NutritionCalculationService} surface on {@link
 * NutritionServiceImpl}. Verifies the four nutrition-status branches (calculated / partial /
 * pending / needs-review), per-serving division (servings=1 and servings=4), micros aggregation,
 * and unmapped-line accumulation.
 */
@ExtendWith(MockitoExtension.class)
class NutritionCalculationServiceTest {

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
  private final HealthDirectiveMapper healthDirectiveMapper = new HealthDirectiveMapper() {};
  private final IntakeKeyNormaliser intakeKeyNormaliser = new IntakeKeyNormaliser();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-09T10:00:00Z"), ZoneOffset.UTC);

  private NutritionServiceImpl service;

  @BeforeEach
  void setUp() {
    org.springframework.beans.factory.support.DefaultListableBeanFactory bf =
        new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    org.springframework.beans.factory.ObjectProvider<
            com.example.mealprep.nutrition.spi.DirectiveApplyTarget>
        emptyProvider =
            bf.getBeanProvider(com.example.mealprep.nutrition.spi.DirectiveApplyTarget.class);
    DirectiveApplier directiveApplier =
        new DirectiveApplier(
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
    service =
        new NutritionServiceImpl(
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
                new java.math.BigDecimal("0.05"),
                new java.math.BigDecimal("0.10"),
                new java.math.BigDecimal("0.20"),
                1000),
            eventPublisher,
            objectMapper,
            fixedClock);
  }

  private IngredientMapping mappingForChicken() {
    return NutritionTestData.ingredientMapping(
        "chicken breast", IngredientMappingSource.USDA, 0.95);
  }

  private IngredientMapping mappingForRice() {
    IngredientNutritionDocument doc =
        new IngredientNutritionDocument(
            130,
            BigDecimal.valueOf(2.7),
            BigDecimal.valueOf(28.0),
            BigDecimal.valueOf(0.3),
            BigDecimal.valueOf(0.4),
            null,
            null,
            new HashMap<>(),
            new HashMap<>());
    return IngredientMapping.builder()
        .id(UUID.randomUUID())
        .searchTerm("rice")
        .source(IngredientMappingSource.USDA)
        .nutritionPer100g(doc)
        .confidence(BigDecimal.valueOf(0.95))
        .needsReview(false)
        .build();
  }

  @Test
  void calculate_allMappedAndVerified_returnsCalculated() {
    UUID recipeId = UUID.randomUUID();
    IngredientMapping chicken = mappingForChicken();
    IngredientMapping rice = mappingForRice();
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(chicken, rice));

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "Chicken breast",
                    "chicken breast",
                    BigDecimal.valueOf(1.0),
                    "piece",
                    BigDecimal.valueOf(200),
                    false),
                new RecipeIngredientLineDto(
                    "Rice",
                    "rice",
                    BigDecimal.valueOf(1.0),
                    "cup",
                    BigDecimal.valueOf(100),
                    false)),
            1);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    assertThat(result.nutritionStatus()).isEqualTo("calculated");
    assertThat(result.unmapped()).isEmpty();
    // chicken 200g: 330 kcal, 62g protein, 0 carbs, 7.2 fat. rice 100g: 130 kcal, 2.7p, 28c, 0.3f.
    assertThat(result.caloriesPerServing()).isEqualTo(460);
    assertThat(result.proteinPerServingG()).isEqualByComparingTo(new BigDecimal("64.70"));
    assertThat(result.carbsPerServingG()).isEqualByComparingTo(new BigDecimal("28.00"));
    assertThat(result.fatPerServingG()).isEqualByComparingTo(new BigDecimal("7.50"));
  }

  @Test
  void calculate_serves4_dividesTotals() {
    UUID recipeId = UUID.randomUUID();
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(mappingForChicken()));

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "chicken",
                    "chicken breast",
                    BigDecimal.valueOf(4.0),
                    "piece",
                    BigDecimal.valueOf(400),
                    false)),
            4);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    // chicken 400g: 660 kcal, 124g protein, 14.4 fat, 0 carbs. /4 servings.
    assertThat(result.caloriesPerServing()).isEqualTo(165);
    assertThat(result.proteinPerServingG()).isEqualByComparingTo(new BigDecimal("31.00"));
    assertThat(result.fatPerServingG()).isEqualByComparingTo(new BigDecimal("3.60"));
  }

  @Test
  void calculate_someUnmapped_returnsPartial() {
    UUID recipeId = UUID.randomUUID();
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(mappingForChicken()));

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "chicken",
                    "chicken breast",
                    BigDecimal.valueOf(1.0),
                    "piece",
                    BigDecimal.valueOf(100),
                    false),
                new RecipeIngredientLineDto(
                    "exotic spice",
                    "exotic-spice",
                    BigDecimal.valueOf(1.0),
                    "tsp",
                    BigDecimal.valueOf(5),
                    false)),
            1);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    assertThat(result.nutritionStatus()).isEqualTo("partial");
    assertThat(result.unmapped()).hasSize(1);
    assertThat(result.unmapped().get(0).name()).isEqualTo("exotic spice");
    assertThat(result.unmapped().get(0).reason()).isEqualTo("not-in-cache");
  }

  @Test
  void calculate_allUnmapped_returnsPending_zeros() {
    UUID recipeId = UUID.randomUUID();
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection())).thenReturn(List.of());

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "mystery",
                    "mystery",
                    BigDecimal.valueOf(1.0),
                    "g",
                    BigDecimal.valueOf(50),
                    false)),
            2);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    assertThat(result.nutritionStatus()).isEqualTo("pending");
    assertThat(result.caloriesPerServing()).isZero();
    assertThat(result.proteinPerServingG()).isEqualByComparingTo("0.00");
    assertThat(result.unmapped()).hasSize(1);
  }

  @Test
  void calculate_anyNeedsReview_returnsPartial() {
    UUID recipeId = UUID.randomUUID();
    IngredientMapping flagged = mappingForChicken();
    flagged.setNeedsReview(true);
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(flagged));

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "chicken",
                    "chicken breast",
                    BigDecimal.valueOf(1.0),
                    "piece",
                    BigDecimal.valueOf(100),
                    false)),
            1);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    assertThat(result.nutritionStatus()).isEqualTo("partial");
    assertThat(result.unmapped()).isEmpty();
  }

  @Test
  void calculate_micros_aggregatedAcrossIngredients() {
    UUID recipeId = UUID.randomUUID();
    Map<String, BigDecimal> chickenMicros = new HashMap<>();
    chickenMicros.put("iron_mg", BigDecimal.valueOf(1.0));
    IngredientNutritionDocument chickenDoc =
        new IngredientNutritionDocument(
            165,
            BigDecimal.valueOf(31.0),
            BigDecimal.ZERO,
            BigDecimal.valueOf(3.6),
            BigDecimal.ZERO,
            null,
            null,
            chickenMicros,
            new HashMap<>());
    IngredientMapping chicken =
        IngredientMapping.builder()
            .id(UUID.randomUUID())
            .searchTerm("chicken breast")
            .source(IngredientMappingSource.USDA)
            .nutritionPer100g(chickenDoc)
            .confidence(BigDecimal.valueOf(0.95))
            .needsReview(false)
            .build();

    Map<String, BigDecimal> spinachMicros = new HashMap<>();
    spinachMicros.put("iron_mg", BigDecimal.valueOf(2.7));
    spinachMicros.put("vitamin_c_mg", BigDecimal.valueOf(28.0));
    IngredientNutritionDocument spinachDoc =
        new IngredientNutritionDocument(
            23,
            BigDecimal.valueOf(2.9),
            BigDecimal.valueOf(3.6),
            BigDecimal.valueOf(0.4),
            BigDecimal.valueOf(2.2),
            null,
            null,
            spinachMicros,
            new HashMap<>());
    IngredientMapping spinach =
        IngredientMapping.builder()
            .id(UUID.randomUUID())
            .searchTerm("spinach")
            .source(IngredientMappingSource.USDA)
            .nutritionPer100g(spinachDoc)
            .confidence(BigDecimal.valueOf(0.95))
            .needsReview(false)
            .build();

    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(chicken, spinach));

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            UUID.randomUUID(),
            List.of(
                new RecipeIngredientLineDto(
                    "chicken",
                    "chicken breast",
                    BigDecimal.valueOf(1.0),
                    "piece",
                    BigDecimal.valueOf(100),
                    false),
                new RecipeIngredientLineDto(
                    "spinach",
                    "spinach",
                    BigDecimal.valueOf(1.0),
                    "cup",
                    BigDecimal.valueOf(100),
                    false)),
            1);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    assertThat(result.nutritionStatus()).isEqualTo("calculated");
    assertThat(result.microsPerServing())
        .containsKeys("iron_mg", "vitamin_c_mg")
        .extractingByKey("iron_mg")
        .matches(
            v ->
                ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP).compareTo(new BigDecimal("3.70"))
                    == 0,
            "iron_mg = 1.0 + 2.7 = 3.70");
    assertThat(result.microsPerServing().get("vitamin_c_mg"))
        .isEqualByComparingTo(new BigDecimal("28.00"));
  }

  @Test
  void calculate_nullGramsEstimate_treatsLineAsZeroContribution() {
    UUID recipeId = UUID.randomUUID();
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(mappingForChicken()));

    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "chicken", "chicken breast", BigDecimal.valueOf(1.0), "piece", null, false)),
            1);

    RecipeNutritionResultDto result = service.calculateRecipeNutrition(req);

    assertThat(result.nutritionStatus()).isEqualTo("calculated");
    assertThat(result.caloriesPerServing()).isZero();
    assertThat(result.proteinPerServingG()).isEqualByComparingTo("0.00");
  }

  @Test
  void recalculate_identicalToCalculate_byMethodName() {
    UUID recipeId = UUID.randomUUID();
    when(ingredientMappingRepository.findBySearchTermIn(anyCollection()))
        .thenReturn(List.of(mappingForChicken()));
    CalculateRecipeNutritionRequest req =
        new CalculateRecipeNutritionRequest(
            recipeId,
            List.of(
                new RecipeIngredientLineDto(
                    "chicken",
                    "chicken breast",
                    BigDecimal.valueOf(1.0),
                    "piece",
                    BigDecimal.valueOf(200),
                    false)),
            1);

    RecipeNutritionResultDto a = service.calculateRecipeNutrition(req);
    RecipeNutritionResultDto b = service.recalculateForEvolvedRecipe(req);

    assertThat(a.caloriesPerServing()).isEqualTo(b.caloriesPerServing());
    assertThat(a.proteinPerServingG()).isEqualByComparingTo(b.proteinPerServingG());
    assertThat(a.nutritionStatus()).isEqualTo(b.nutritionStatus());
  }
}
