package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.mapper.DailyActivityMapper;
import com.example.mealprep.nutrition.api.mapper.HealthDirectiveMapper;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.api.mapper.IntakeMapper;
import com.example.mealprep.nutrition.api.mapper.JournalMapper;
import com.example.mealprep.nutrition.api.mapper.TargetsMapper;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit coverage of the v1 recipe-vs-targets violation pre-filter ({@code
 * NutritionQueryService.findRecipeIdsViolatingTargets}) exercised through {@link
 * NutritionServiceImpl}. The targets repository is mocked; per-serving JSON inputs are crafted with
 * a Jackson {@link ObjectMapper} matching {@code RecipeNutritionResultDto}'s field names.
 *
 * <p>Default targets (from {@link NutritionTestData}): dailyCalorieTarget=2000, calorieDirection
 * BOTH_BOUNDED, calorieToleranceOver=150; protein target 120g LOWER_FLOOR (no floor set by
 * default); fat target 70g BOTH_BOUNDED; fibre target 30g LOWER_FLOOR; satFat target 20g
 * UPPER_LIMIT. With the default 4-slot per-meal distribution, the largest slot is DINNER=700kcal →
 * mealShare = 700/2000 = 0.35.
 */
@ExtendWith(MockitoExtension.class)
class RecipeTargetViolationFilterTest {

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
            new BigDecimal("0.15"),
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
                new BigDecimal("0.05"), new BigDecimal("0.10"), new BigDecimal("0.20"), 1000),
            eventPublisher,
            objectMapper,
            fixedClock);
  }

  // ---------------- helpers ----------------

  /** A full, valid per-serving JSON object that is comfortably within all default targets. */
  private ObjectNode wellWithinAllTargets() {
    ObjectNode n = objectMapper.createObjectNode();
    n.put("caloriesPerServing", 500); // < 0.35*2000=700
    n.put("proteinPerServingG", new BigDecimal("40.0"));
    n.put("carbsPerServingG", new BigDecimal("60.0"));
    n.put("fatPerServingG", new BigDecimal("15.0")); // < 0.35*70=24.5
    n.put("fibrePerServingG", new BigDecimal("8.0"));
    n.putObject("microsPerServing");
    return n;
  }

  private static Map<UUID, JsonNode> single(UUID id, JsonNode node) {
    Map<UUID, JsonNode> m = new LinkedHashMap<>();
    m.put(id, node);
    return m;
  }

  // ---------------- tests ----------------

  @Test
  void clearlyOverCalories_violates() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30.0))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    n.put("caloriesPerServing", 1500); // mealShare 0.35 → allowance 700 (+ tol 52.5) → violates

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).containsExactly(recipeId);
  }

  @Test
  void caloriesWithinToleranceBand_notViolated() {
    UUID userId = UUID.randomUUID();
    // No per-meal distribution → DEFAULT_MEAL_SHARE 0.4 → allowance 0.4*2000=800,
    // tolerance 0.4*150=60 → ceiling 860.
    NutritionTargets targets = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    n.put("caloriesPerServing", 850); // within 860 ceiling → not flagged

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).isEmpty();
  }

  @Test
  void proteinClearlyUnderFloor_violates() {
    UUID userId = UUID.randomUUID();
    // Floor 100g daily, default mealShare 0.4 → per-meal floor 40g; protein direction LOWER_FLOOR.
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    n.put("proteinPerServingG", new BigDecimal("5.0")); // << 40g floor → violates

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).containsExactly(recipeId);
  }

  @Test
  void proteinFloorNotConfigured_neverFlagsOnProtein() {
    UUID userId = UUID.randomUUID();
    // Default builder leaves proteinFloorG null → no floor to violate even at 0g.
    NutritionTargets targets = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    n.put("proteinPerServingG", new BigDecimal("0.0"));

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).isEmpty();
  }

  @Test
  void macroNotEnforcedAsCeiling_overValue_notFlagged() {
    UUID userId = UUID.randomUUID();
    // satFat direction defaults to UPPER_LIMIT; here we prove the inverse: protein is LOWER_FLOOR,
    // so a HUGE protein value (a "ceiling" reading) must NOT flag it — protein has no ceiling rule.
    NutritionTargets targets = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    n.put("proteinPerServingG", new BigDecimal("500.0")); // way over, but protein is floor-only

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).isEmpty();
  }

  @Test
  void emptyAndMissingMacroFields_gracefulNotFlagged() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    // An object with no macro fields at all — every macro is "no data" → not flagged.
    ObjectNode n = objectMapper.createObjectNode();

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).isEmpty();
  }

  @Test
  void noTargetsRow_returnsEmptySet() {
    UUID userId = UUID.randomUUID();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    n.put("caloriesPerServing", 99999); // clearly over, but no targets → nothing to violate

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).isEmpty();
  }

  @Test
  void nullOrEmptyInput_returnsEmptySet() {
    UUID userId = UUID.randomUUID();

    assertThat(service.findRecipeIdsViolatingTargets(userId, null)).isEmpty();
    assertThat(service.findRecipeIdsViolatingTargets(userId, Map.of())).isEmpty();
  }

  @Test
  void nullJsonNodeValue_skipsThatRecipe_othersStillEvaluated() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID skipped = UUID.randomUUID();
    UUID violatingId = UUID.randomUUID();
    UUID okId = UUID.randomUUID();

    ObjectNode over = wellWithinAllTargets();
    over.put("caloriesPerServing", 2000); // clearly over → violates

    Map<UUID, JsonNode> input = new LinkedHashMap<>();
    input.put(skipped, null); // skipped — null value
    input.put(violatingId, over);
    input.put(okId, wellWithinAllTargets());

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, input);

    assertThat(result).containsExactly(violatingId);
  }

  @Test
  void perMealDistributionPresent_usesLargestSlotShare_notDefault() {
    UUID userId = UUID.randomUUID();
    // Largest slot DINNER=700 → mealShare 0.35 → calorie allowance 700 (+ tol 0.35*150=52.5)=752.5.
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withPerMeal(MealSlot.BREAKFAST, 400, BigDecimal.valueOf(30.0))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID underDefaultButOverSlot = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    // 790 would PASS the DEFAULT_MEAL_SHARE ceiling (860) but FAILS the 752.5 slot-derived ceiling.
    n.put("caloriesPerServing", 790);

    Set<UUID> result =
        service.findRecipeIdsViolatingTargets(userId, single(underDefaultButOverSlot, n));

    assertThat(result).containsExactly(underDefaultButOverSlot);
  }

  @Test
  void defaultMealSharePath_whenNoPerMealDistribution() {
    UUID userId = UUID.randomUUID();
    NutritionTargets targets = NutritionTestData.targets().withUserId(userId).build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    UUID recipeId = UUID.randomUUID();
    ObjectNode n = wellWithinAllTargets();
    // DEFAULT_MEAL_SHARE 0.4 → ceiling 800 + tol 60 = 860; 900 is clearly over.
    n.put("caloriesPerServing", 900);

    Set<UUID> result = service.findRecipeIdsViolatingTargets(userId, single(recipeId, n));

    assertThat(result).containsExactly(recipeId);
  }
}
