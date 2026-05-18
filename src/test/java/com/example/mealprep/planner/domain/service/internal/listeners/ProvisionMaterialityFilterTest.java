package com.example.mealprep.planner.domain.service.internal.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.provisions.event.ItemAddedFromGroceryEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.event.ItemSpoiledEvent;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit test for {@link ProvisionMaterialityFilter} — recipe-ingredient match + over-trigger. */
@ExtendWith(MockitoExtension.class)
class ProvisionMaterialityFilterTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  @Mock private RecipeQueryService recipeQueryService;
  private ProvisionMaterialityFilter filter;

  @BeforeEach
  void setUp() {
    filter = new ProvisionMaterialityFilter(recipeQueryService);
  }

  private Plan planWithOnePlannedSlot() {
    return PlanTestData.newPlanGraph(UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 1, 1);
  }

  private UUID firstScheduledRecipeId(Plan plan) {
    return plan.getDays().get(0).getSlots().get(0).getScheduledRecipe().getRecipeId();
  }

  @Test
  void itemAddedFromGrocery_isNeverMaterial() {
    Plan plan = planWithOnePlannedSlot();
    ItemAddedFromGroceryEvent event =
        new ItemAddedFromGroceryEvent(
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "Tesco",
            "ref-1",
            UUID.randomUUID(),
            Instant.now());

    assertThat(filter.isMaterial(event, plan)).isFalse();
  }

  @Test
  void itemRanOut_isMaterial_whenARemainingSlotRecipeUsesTheKey() {
    Plan plan = planWithOnePlannedSlot();
    UUID recipeId = firstScheduledRecipeId(plan);
    RecipeDto recipe =
        PlanTestData.recipeFor(
            recipeId, SlotKind.BREAKFAST, 20, List.of(), List.of("eggs", "milk"));
    when(recipeQueryService.getById(recipeId)).thenReturn(Optional.of(recipe));

    ItemRanOutEvent event =
        new ItemRanOutEvent(
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "milk",
            false,
            UUID.randomUUID(),
            Instant.now());

    assertThat(filter.isMaterial(event, plan)).isTrue();
  }

  @Test
  void itemRanOut_isImmaterial_whenNoRemainingSlotRecipeUsesTheKey() {
    Plan plan = planWithOnePlannedSlot();
    UUID recipeId = firstScheduledRecipeId(plan);
    RecipeDto recipe =
        PlanTestData.recipeFor(recipeId, SlotKind.BREAKFAST, 20, List.of(), List.of("eggs"));
    when(recipeQueryService.getById(recipeId)).thenReturn(Optional.of(recipe));

    ItemRanOutEvent event =
        new ItemRanOutEvent(
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "saffron",
            false,
            UUID.randomUUID(),
            Instant.now());

    assertThat(filter.isMaterial(event, plan)).isFalse();
  }

  @Test
  void itemRanOut_isImmaterial_whenAllSlotsAreCookedOrEaten() {
    Plan plan = planWithOnePlannedSlot();
    plan.getDays().get(0).getSlots().get(0).setState(SlotState.EATEN);

    ItemRanOutEvent event =
        new ItemRanOutEvent(
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "milk",
            false,
            UUID.randomUUID(),
            Instant.now());

    // No regenerable slot -> short-circuits before any recipe lookup.
    assertThat(filter.isMaterial(event, plan)).isFalse();
  }

  @Test
  void itemSpoiled_overTriggers_whenAPlannedSlotExists() {
    Plan plan = planWithOnePlannedSlot();
    // Spoiled carries no mapping key -> conservative over-trigger, no recipe lookup needed.
    ItemSpoiledEvent event =
        new ItemSpoiledEvent(
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "fridge failure",
            UUID.randomUUID(),
            Instant.now());

    assertThat(filter.isMaterial(event, plan)).isTrue();
  }

  @Test
  void itemRanOut_overTriggers_whenRecipeLookupThrows() {
    Plan plan = planWithOnePlannedSlot();
    UUID recipeId = firstScheduledRecipeId(plan);
    lenient()
        .when(recipeQueryService.getById(any(UUID.class)))
        .thenThrow(new RuntimeException("recipe service down"));

    ItemRanOutEvent event =
        new ItemRanOutEvent(
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "milk",
            false,
            UUID.randomUUID(),
            Instant.now());

    assertThat(filter.isMaterial(event, plan)).isTrue();
    // recipeId referenced so the helper stays meaningful even if the impl changes lookup strategy
    assertThat(recipeId).isNotNull();
  }
}
