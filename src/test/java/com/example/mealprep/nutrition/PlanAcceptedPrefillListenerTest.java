package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.domain.service.internal.PlanAcceptedPrefillListener;
import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.AdditionKind;
import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ScheduledRecipeDto;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.event.PlanAcceptedEvent;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * Unit coverage for {@link PlanAcceptedPrefillListener}: slot-kind mapping, per-person portion
 * arithmetic, per-eater fan-out, and the never-rethrow guarantee. Lenient stubs: the DTO mock
 * helpers stub accessors a skipped (SNACK/CUSTOM) slot never reaches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanAcceptedPrefillListenerTest {

  @Mock private PlanQueryService planQueryService;
  @Mock private RecipeQueryService recipeQueryService;
  @Mock private NutritionUpdateService nutritionUpdateService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final UUID PLAN_ID = UUID.randomUUID();
  private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
  private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

  private PlanAcceptedPrefillListener listener() {
    return new PlanAcceptedPrefillListener(
        planQueryService, recipeQueryService, nutritionUpdateService, objectMapper);
  }

  private static PlanAcceptedEvent event() {
    return new PlanAcceptedEvent(
        PLAN_ID, HOUSEHOLD_ID, MONDAY, UUID.randomUUID(), Instant.parse("2026-08-24T09:00:00Z"));
  }

  private static PlanDto plan(List<DayDto> days) {
    PlanDto plan = mock(PlanDto.class);
    when(plan.id()).thenReturn(PLAN_ID);
    when(plan.days()).thenReturn(days);
    return plan;
  }

  private static MealSlotDto slot(SlotKind kind, List<UUID> eaters, ScheduledRecipeDto scheduled) {
    MealSlotDto slot = mock(MealSlotDto.class);
    when(slot.kind()).thenReturn(kind);
    when(slot.eaters()).thenReturn(eaters);
    when(slot.scheduledRecipe()).thenReturn(scheduled);
    return slot;
  }

  private static DayDto day(LocalDate date, List<MealSlotDto> slots) {
    DayDto day = mock(DayDto.class);
    when(day.date()).thenReturn(date);
    when(day.slots()).thenReturn(slots);
    return day;
  }

  private static ScheduledRecipeDto scheduled(UUID recipeId, BigDecimal portionFactor) {
    ScheduledRecipeDto sr = mock(ScheduledRecipeDto.class);
    when(sr.recipeId()).thenReturn(recipeId);
    when(sr.portionFactor()).thenReturn(portionFactor);
    when(sr.additions()).thenReturn(List.of());
    return sr;
  }

  private void stubRecipeNutrition(UUID recipeId, NutritionPerServingDto perServing) {
    RecipeDto recipe = mock(RecipeDto.class);
    RecipeVersionDto version = mock(RecipeVersionDto.class);
    when(recipe.currentVersionBody()).thenReturn(version);
    when(version.nutritionPerServing()).thenReturn(perServing);
    when(recipeQueryService.getById(recipeId)).thenReturn(Optional.of(recipe));
  }

  @Test
  void onPlanAccepted_prefillsScaledPerServingNutrition_perEaterPerDay() {
    UUID eater = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    stubRecipeNutrition(
        recipeId,
        new NutritionPerServingDto(
            400,
            new BigDecimal("30.0"),
            new BigDecimal("50.0"),
            new BigDecimal("12.0"),
            null,
            Map.of("iron_mg", new BigDecimal("4.0"))));
    MealSlotDto breakfast =
        slot(SlotKind.BREAKFAST, List.of(eater), scheduled(recipeId, new BigDecimal("1.5")));
    PlanDto planDto = plan(List.of(day(MONDAY, List.of(breakfast))));
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(planDto));

    listener().onPlanAccepted(event());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PlannedSlotInputDto>> slots = ArgumentCaptor.forClass(List.class);
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eater), eq(MONDAY), eq(PLAN_ID), slots.capture());
    assertThat(slots.getValue()).hasSize(1);
    PlannedSlotInputDto in = slots.getValue().get(0);
    assertThat(in.mealSlot()).isEqualTo(MealSlot.BREAKFAST);
    assertThat(in.plannedRecipeId()).isEqualTo(recipeId);
    // One serving scaled by the portion factor 1.5: 400 -> 600 kcal, 30 -> 45.0 g etc.
    assertThat(in.plannedCalories()).isEqualTo(600);
    assertThat(in.plannedProteinG()).isEqualByComparingTo("45.0");
    assertThat(in.plannedCarbsG()).isEqualByComparingTo("75.0");
    assertThat(in.plannedFatG()).isEqualByComparingTo("18.0");
    // fibre was null per serving: stays null, not zero-filled.
    assertThat(in.plannedFibreG()).isNull();
    assertThat(in.plannedMicros().get("iron_mg").decimalValue())
        .isEqualByComparingTo(new BigDecimal("6.000"));
    assertThat(in.plannedMicros().size()).isEqualTo(1);
  }

  @Test
  void onPlanAccepted_additionsSummedVerbatim_notPortionScaled() {
    UUID eater = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    stubRecipeNutrition(
        recipeId,
        new NutritionPerServingDto(
            400,
            new BigDecimal("30.0"),
            null,
            null,
            null,
            Map.of("iron_mg", new BigDecimal("4.0"))));
    Addition addition =
        new Addition(
            AdditionKind.INGREDIENT,
            "half avocado",
            "avocado",
            null,
            new BigDecimal("0.5"),
            "whole",
            new BigDecimal("100"),
            new NutritionPerServingDto(
                160,
                new BigDecimal("2.0"),
                null,
                null,
                new BigDecimal("6.7"),
                Map.of("potassium_mg", new BigDecimal("485.0"))),
            "on the toast");
    ScheduledRecipeDto sr = mock(ScheduledRecipeDto.class);
    when(sr.recipeId()).thenReturn(recipeId);
    when(sr.portionFactor()).thenReturn(new BigDecimal("2.0"));
    when(sr.additions()).thenReturn(List.of(addition));
    MealSlotDto lunch = slot(SlotKind.LUNCH, List.of(eater), sr);
    PlanDto planDto = plan(List.of(day(MONDAY, List.of(lunch))));
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(planDto));

    listener().onPlanAccepted(event());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PlannedSlotInputDto>> slots = ArgumentCaptor.forClass(List.class);
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eater), eq(MONDAY), eq(PLAN_ID), slots.capture());
    PlannedSlotInputDto in = slots.getValue().get(0);
    // Main scaled x2 (400 -> 800), addition added verbatim (160): 960 kcal.
    assertThat(in.plannedCalories()).isEqualTo(960);
    // Protein: 30 x 2 + 2 = 62.0.
    assertThat(in.plannedProteinG()).isEqualByComparingTo("62.0");
    // Fibre only from the addition: 6.7, not scaled.
    assertThat(in.plannedFibreG()).isEqualByComparingTo("6.7");
    // Micros: scaled main + verbatim addition, distinct keys both present.
    assertThat(in.plannedMicros().get("iron_mg").decimalValue())
        .isEqualByComparingTo(new BigDecimal("8.000"));
    assertThat(in.plannedMicros().get("potassium_mg").decimalValue())
        .isEqualByComparingTo(new BigDecimal("485.000"));
  }

  @Test
  void onPlanAccepted_snackCustomNullKindAndNullEaterSlots_notPrefilled() {
    UUID eater = UUID.randomUUID();
    MealSlotDto snack = slot(SlotKind.SNACK, List.of(eater), null);
    MealSlotDto custom = slot(SlotKind.CUSTOM, List.of(eater), null);
    MealSlotDto kindless = slot(null, List.of(eater), null);
    MealSlotDto eaterless = slot(SlotKind.LUNCH, null, null);
    MealSlotDto dinner = slot(SlotKind.DINNER, List.of(eater), null);
    PlanDto planDto = plan(List.of(day(MONDAY, List.of(snack, custom, dinner))));
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(planDto));

    listener().onPlanAccepted(event());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PlannedSlotInputDto>> slots = ArgumentCaptor.forClass(List.class);
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eater), eq(MONDAY), eq(PLAN_ID), slots.capture());
    // Only the dinner slot arrives; the empty slot pre-fills with null planned figures.
    assertThat(slots.getValue()).hasSize(1);
    PlannedSlotInputDto in = slots.getValue().get(0);
    assertThat(in.mealSlot()).isEqualTo(MealSlot.DINNER);
    assertThat(in.plannedRecipeId()).isNull();
    assertThat(in.plannedCalories()).isNull();
    assertThat(in.plannedMicros()).isNull();
  }

  @Test
  void onPlanAccepted_multipleEatersAndDays_oneCallPerEaterDay() {
    UUID eaterA = UUID.randomUUID();
    UUID eaterB = UUID.randomUUID();
    MealSlotDto sharedDinner = slot(SlotKind.DINNER, List.of(eaterA, eaterB), null);
    MealSlotDto soloLunch = slot(SlotKind.LUNCH, List.of(eaterA), null);
    PlanDto planDto =
        plan(
            List.of(
                day(MONDAY, List.of(sharedDinner, soloLunch)),
                day(MONDAY.plusDays(1), List.of(sharedDinner))));
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(planDto));

    listener().onPlanAccepted(event());

    verify(nutritionUpdateService).prefillFromPlan(eq(eaterA), eq(MONDAY), eq(PLAN_ID), any());
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eaterA), eq(MONDAY.plusDays(1)), eq(PLAN_ID), any());
    verify(nutritionUpdateService).prefillFromPlan(eq(eaterB), eq(MONDAY), eq(PLAN_ID), any());
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eaterB), eq(MONDAY.plusDays(1)), eq(PLAN_ID), any());
    verify(nutritionUpdateService, times(4)).prefillFromPlan(any(), any(), any(), any());
  }

  @Test
  void onPlanAccepted_planMissing_noPrefillAndNoThrow() {
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.empty());

    listener().onPlanAccepted(event());

    verifyNoInteractions(nutritionUpdateService);
    verifyNoInteractions(recipeQueryService);
  }

  @Test
  void onPlanAccepted_oneDayFails_othersStillPrefilled_nothingRethrown() {
    UUID eater = UUID.randomUUID();
    MealSlotDto dinner = slot(SlotKind.DINNER, List.of(eater), null);
    PlanDto planDto =
        plan(List.of(day(MONDAY, List.of(dinner)), day(MONDAY.plusDays(1), List.of(dinner))));
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(planDto));
    when(nutritionUpdateService.prefillFromPlan(eq(eater), eq(MONDAY), eq(PLAN_ID), any()))
        .thenThrow(new IllegalStateException("boom"));

    listener().onPlanAccepted(event());

    // The Tuesday pre-fill still runs despite Monday blowing up.
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eater), eq(MONDAY.plusDays(1)), eq(PLAN_ID), any());
  }

  @Test
  void onPlanAccepted_recipeWithoutComputedNutrition_prefillsNullFigures_notZeros() {
    UUID eater = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    RecipeDto recipe = mock(RecipeDto.class);
    when(recipe.currentVersionBody()).thenReturn(null);
    when(recipeQueryService.getById(recipeId)).thenReturn(Optional.of(recipe));
    MealSlotDto breakfast =
        slot(SlotKind.BREAKFAST, List.of(eater), scheduled(recipeId, BigDecimal.ONE));
    PlanDto planDto = plan(List.of(day(MONDAY, List.of(breakfast))));
    when(planQueryService.getPlanById(PLAN_ID)).thenReturn(Optional.of(planDto));

    listener().onPlanAccepted(event());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PlannedSlotInputDto>> slots = ArgumentCaptor.forClass(List.class);
    verify(nutritionUpdateService)
        .prefillFromPlan(eq(eater), eq(MONDAY), eq(PLAN_ID), slots.capture());
    PlannedSlotInputDto in = slots.getValue().get(0);
    assertThat(in.plannedRecipeId()).isEqualTo(recipeId);
    assertThat(in.plannedCalories()).isNull();
    assertThat(in.plannedProteinG()).isNull();
    assertThat(in.plannedMicros()).isNull();
    // The recipe was looked up exactly once (cached per event).
    verify(recipeQueryService, times(1)).getById(recipeId);
  }
}
