package com.example.mealprep.nutrition.testdata;

import com.example.mealprep.nutrition.api.dto.ActivityAdjustmentDto;
import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.EatingWindowDto;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.MicroTargetDto;
import com.example.mealprep.nutrition.api.dto.PerMealDistributionDto;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.dto.UpsertFoodMoodEntryRequest;
import com.example.mealprep.nutrition.domain.entity.ActivityAdjustment;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.entity.EatingWindow;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Test Data Builder for the nutrition module. Defaults are concrete and pass validators so callers
 * tweak only the field under test.
 */
public final class NutritionTestData {

  private NutritionTestData() {}

  public static NutritionTargetsBuilder targets() {
    return new NutritionTargetsBuilder();
  }

  public static UpdateTargetsRequest defaultUpdateRequest(long expectedVersion) {
    return new UpdateTargetsRequest(
        Goal.MAINTAIN,
        defaultCalories(),
        new MacroTargetDto(
            BigDecimal.valueOf(120.0), null, "daily_floor", EnforcementDirection.LOWER_FLOOR),
        new MacroTargetDto(
            BigDecimal.valueOf(250.0), null, "weekly_average", EnforcementDirection.BOTH_BOUNDED),
        new MacroTargetDto(
            BigDecimal.valueOf(70.0), null, "weekly_average", EnforcementDirection.BOTH_BOUNDED),
        new MacroTargetDto(
            BigDecimal.valueOf(30.0), null, "daily_floor", EnforcementDirection.LOWER_FLOOR),
        new MacroTargetDto(BigDecimal.valueOf(20.0), null, null, EnforcementDirection.UPPER_LIMIT),
        "Default notes",
        defaultPerMealList(),
        defaultMicros(),
        new EatingWindowDto(false, null, null, null),
        defaultActivities(),
        expectedVersion);
  }

  public static CalorieTargetDto defaultCalories() {
    return new CalorieTargetDto(
        2000, 100, 150, "weekly_average", EnforcementDirection.BOTH_BOUNDED);
  }

  public static MacroTargetDto defaultMacro(BigDecimal target) {
    return new MacroTargetDto(target, null, "weekly_average", EnforcementDirection.BOTH_BOUNDED);
  }

  public static List<PerMealDistributionDto> defaultPerMealList() {
    List<PerMealDistributionDto> list = new ArrayList<>();
    list.add(new PerMealDistributionDto(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30.0)));
    list.add(new PerMealDistributionDto(MealSlot.LUNCH, 600, BigDecimal.valueOf(40.0)));
    list.add(new PerMealDistributionDto(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0)));
    list.add(new PerMealDistributionDto(MealSlot.SNACKS, 200, BigDecimal.valueOf(10.0)));
    return list;
  }

  public static List<MicroTargetDto> defaultMicros() {
    List<MicroTargetDto> list = new ArrayList<>();
    list.add(new MicroTargetDto("iron_mg", BigDecimal.valueOf(18.0), null, null, null));
    list.add(new MicroTargetDto("vitamin_d_iu", BigDecimal.valueOf(800.0), null, null, null));
    return list;
  }

  public static List<ActivityAdjustmentDto> defaultActivities() {
    List<ActivityAdjustmentDto> list = new ArrayList<>();
    list.add(new ActivityAdjustmentDto(ActivityLevel.REST_DAY, -200, -30));
    list.add(new ActivityAdjustmentDto(ActivityLevel.TRAINING_DAY, 300, 50));
    return list;
  }

  // ---------------- 01b: intake + activity fixtures ----------------

  /** A reasonable {@code LogSnackRequest} with all required fields populated. */
  public static LogSnackRequest defaultSnackRequest() {
    return new LogSnackRequest(
        "almonds",
        null,
        BigDecimal.valueOf(30.0),
        180,
        BigDecimal.valueOf(7.0),
        BigDecimal.valueOf(6.0),
        BigDecimal.valueOf(15.0),
        BigDecimal.valueOf(3.0),
        null,
        IntakeSource.MANUAL,
        null);
  }

  /** A reasonable list of planned-slot inputs covering all four meal slots. */
  public static List<PlannedSlotInputDto> defaultPlannedSlots() {
    List<PlannedSlotInputDto> out = new ArrayList<>();
    out.add(
        new PlannedSlotInputDto(
            MealSlot.BREAKFAST,
            null,
            500,
            BigDecimal.valueOf(30.0),
            BigDecimal.valueOf(60.0),
            BigDecimal.valueOf(15.0),
            BigDecimal.valueOf(8.0),
            null));
    out.add(
        new PlannedSlotInputDto(
            MealSlot.LUNCH,
            null,
            600,
            BigDecimal.valueOf(40.0),
            BigDecimal.valueOf(70.0),
            BigDecimal.valueOf(20.0),
            BigDecimal.valueOf(10.0),
            null));
    out.add(
        new PlannedSlotInputDto(
            MealSlot.DINNER,
            null,
            700,
            BigDecimal.valueOf(40.0),
            BigDecimal.valueOf(80.0),
            BigDecimal.valueOf(25.0),
            BigDecimal.valueOf(12.0),
            null));
    return out;
  }

  // ---------------- 01c: journal fixtures ----------------

  /** A reasonable {@code UpsertFoodMoodEntryRequest} tied to a meal slot. */
  public static UpsertFoodMoodEntryRequest defaultJournalRequest(LocalDate onDate) {
    return new UpsertFoodMoodEntryRequest(
        onDate, MealSlot.LUNCH, "felt good after lunch", Instant.parse("2026-05-09T12:30:00Z"), 0L);
  }

  /** A journal request with a specific meal slot (or null for untied). */
  public static UpsertFoodMoodEntryRequest journalRequest(
      LocalDate onDate, MealSlot mealSlot, String text, long expectedVersion) {
    return new UpsertFoodMoodEntryRequest(
        onDate, mealSlot, text, Instant.parse("2026-05-09T12:30:00Z"), expectedVersion);
  }

  // ---------------- 01d: ingredient-mapping fixtures ----------------

  public static IngredientNutritionDocument defaultNutritionDocument() {
    return new IngredientNutritionDocument(
        165,
        BigDecimal.valueOf(31.0),
        BigDecimal.valueOf(0.0),
        BigDecimal.valueOf(3.6),
        BigDecimal.valueOf(0.0),
        BigDecimal.valueOf(1.0),
        BigDecimal.valueOf(0.0),
        new HashMap<>(),
        new HashMap<>());
  }

  public static IngredientMapping ingredientMapping(
      String searchTerm, IngredientMappingSource source, double confidence) {
    return IngredientMapping.builder()
        .id(UUID.randomUUID())
        .searchTerm(searchTerm)
        .source(source)
        .externalId("12345")
        .nutritionPer100g(defaultNutritionDocument())
        .confidence(BigDecimal.valueOf(confidence))
        .needsReview(confidence < 0.7)
        .build();
  }

  // ---------------- Entity builders (for unit tests) ----------------

  public static final class NutritionTargetsBuilder {
    private UUID id = UUID.randomUUID();
    private UUID userId = UUID.randomUUID();
    private long version = 0L;
    private final List<PerMealDistributionEntry> perMeal = new ArrayList<>();
    private final List<MicroTarget> micros = new ArrayList<>();
    private final List<ActivityAdjustment> activities = new ArrayList<>();
    private EatingWindow eatingWindow;

    public NutritionTargetsBuilder withId(UUID id) {
      this.id = id;
      return this;
    }

    public NutritionTargetsBuilder withUserId(UUID userId) {
      this.userId = userId;
      return this;
    }

    public NutritionTargetsBuilder withVersion(long version) {
      this.version = version;
      return this;
    }

    public NutritionTargetsBuilder withPerMeal(MealSlot slot, int calories, BigDecimal protein) {
      perMeal.add(
          PerMealDistributionEntry.builder()
              .id(UUID.randomUUID())
              .mealSlot(slot)
              .calorieTarget(calories)
              .proteinTargetG(protein)
              .build());
      return this;
    }

    public NutritionTargetsBuilder withMicro(String key, BigDecimal target) {
      micros.add(
          MicroTarget.builder().id(UUID.randomUUID()).nutrientKey(key).targetValue(target).build());
      return this;
    }

    public NutritionTargetsBuilder withActivity(ActivityLevel level, int calories, int carbs) {
      activities.add(
          ActivityAdjustment.builder()
              .id(UUID.randomUUID())
              .activityLevel(level)
              .calorieModifier(calories)
              .carbModifierG(carbs)
              .build());
      return this;
    }

    public NutritionTargetsBuilder withEatingWindow(boolean enabled) {
      this.eatingWindow = EatingWindow.builder().id(UUID.randomUUID()).enabled(enabled).build();
      return this;
    }

    public NutritionTargets build() {
      NutritionTargets t =
          NutritionTargets.builder()
              .id(id)
              .userId(userId)
              .goal(Goal.MAINTAIN)
              .dailyCalorieTarget(2000)
              .calorieToleranceUnder(100)
              .calorieToleranceOver(150)
              .calorieEnforcement("weekly_average")
              .calorieDirection(EnforcementDirection.BOTH_BOUNDED)
              .proteinTargetG(BigDecimal.valueOf(120.0))
              .proteinFloorG(null)
              .proteinEnforcement("daily_floor")
              .proteinDirection(EnforcementDirection.LOWER_FLOOR)
              .carbsTargetG(BigDecimal.valueOf(250.0))
              .carbsFloorG(null)
              .carbsEnforcement("weekly_average")
              .carbsDirection(EnforcementDirection.BOTH_BOUNDED)
              .fatTargetG(BigDecimal.valueOf(70.0))
              .fatFloorG(null)
              .fatEnforcement("weekly_average")
              .fatDirection(EnforcementDirection.BOTH_BOUNDED)
              .fibreTargetG(BigDecimal.valueOf(30.0))
              .fibreFloorG(null)
              .fibreEnforcement("daily_floor")
              .fibreDirection(EnforcementDirection.LOWER_FLOOR)
              .satFatTargetG(BigDecimal.valueOf(20.0))
              .satFatDirection(EnforcementDirection.UPPER_LIMIT)
              .notes(null)
              .userOverriddenDirections(new ArrayList<>())
              .perMealDistribution(new ArrayList<>())
              .microTargets(new ArrayList<>())
              .activityAdjustments(new ArrayList<>())
              .eatingWindow(null)
              .version(version)
              .build();
      for (PerMealDistributionEntry e : perMeal) {
        e.setTarget(t);
        t.getPerMealDistribution().add(e);
      }
      for (MicroTarget m : micros) {
        m.setTarget(t);
        t.getMicroTargets().add(m);
      }
      for (ActivityAdjustment a : activities) {
        a.setTarget(t);
        t.getActivityAdjustments().add(a);
      }
      if (eatingWindow != null) {
        eatingWindow.setTarget(t);
        t.setEatingWindow(eatingWindow);
      }
      return t;
    }
  }
}
