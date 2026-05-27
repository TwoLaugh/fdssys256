package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
import com.example.mealprep.grocery.api.dto.ShoppingListDto;
import com.example.mealprep.grocery.domain.service.ShoppingListService;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.DayDto;
import com.example.mealprep.planner.api.dto.MealSlotDto;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ScheduledRecipeDto;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.testsupport.TestContainersConfig;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Calculator integration test against real Postgres (Testcontainers) — grocery-01b. Asserts the
 * ≤5-SQL-statements-per-recalculate trip-wire (LLD line 871) via Hibernate {@link Statistics}, and
 * a true end-to-end recalculate persisting a shopping list. The peer query services (plan, recipe,
 * inventory, lifestyle, household) are supplied as {@code @Primary} Mockito mocks so the SQL
 * counted is the GROCERY-owned work (pack-size lookups + the one batched price-aggregate query +
 * the list insert + the supersede check); each peer read is "one round trip" exactly as the LLD
 * models.
 *
 * <p>The grocery-owned reference data (pack-size heuristics, reference prices) is real, loaded by
 * the {@code R__grocery_seed_*} migrations.
 */
@SpringBootTest
@Import({TestContainersConfig.class, ShoppingListCalculatorIT.PeerMockConfig.class})
@ActiveProfiles("test")
class ShoppingListCalculatorIT {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID PLAN = UUID.randomUUID();

  @Autowired private ShoppingListService shoppingListService;
  @Autowired private ShoppingListCalculator shoppingListCalculator;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlanQueryService planQueryService;
  @Autowired private RecipeQueryService recipeQueryService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    Mockito.reset(planQueryService, recipeQueryService);
  }

  @Test
  void recalculate_persistsListFromPlanWalk_withFlourLine() {
    stubPlanWithOneRecipe("flour", "750", "g", 1, 1);

    ShoppingListDto dto =
        shoppingListService.recalculate(USER, new RecalculateShoppingListRequest(PLAN, 1));

    assertThat(dto.planGeneration()).isEqualTo(1);
    assertThat(dto.lines()).extracting(l -> l.ingredientMappingKey()).contains("flour");
    // The seeded pack heuristics give flour {500,1000,1500}; 750 g → 1×1 kg.
    assertThat(dto.lines().get(0).suggestedPackSizeG()).isEqualTo(1000);
    assertThat(dto.lines().get(0).suggestedPackCount()).isEqualTo(1);
  }

  @Test
  void calculate_issuesAtMostFiveSqlStatements() {
    // LLD line 871: "ShoppingListCalculator.calculate should be ≤5 SQL statements end-to-end —
    // verified via Hibernate statistics." We measure calculate() (the LLD's named subject), with
    // the peer reads mocked (each one logical round trip, as the LLD models: the plan bundle and
    // the inventory read each take one trip; the price aggregates are batched into one query).
    PlanDto plan = planWith(1, slot(recipe(recipeId, "flour", "750", "g", 1), 1));
    when(recipeQueryService.getById(recipeId))
        .thenReturn(Optional.of(recipe(recipeId, "flour", "750", "g", 1)));

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();

    shoppingListCalculator.calculate(USER, plan, 1);

    long statements = stats.getPrepareStatementCount();
    // The grocery-owned reads inside calculate: pack-size lookup (1) + the batched price-aggregate
    // observation query (1) + the reference batch (1) = 3. Inventory is gated off (no lifestyle
    // config) and peer reads issue no SQL here (mocked). ≤5 is the trip-wire.
    assertThat(statements)
        .as("calculate() issued %d SQL statements (LLD ≤5 trip-wire)", statements)
        .isLessThanOrEqualTo(5);
  }

  @Test
  void recalculate_distinctRecipesReadAtMostOncePerRecipe() {
    stubPlanWithSameRecipeInThreeSlots("flour", "100", "g", 1);

    shoppingListService.recalculate(USER, new RecalculateShoppingListRequest(PLAN, 1));

    // The plan schedules ONE recipe in three slots → exactly one recipe read.
    Mockito.verify(recipeQueryService, Mockito.times(1)).getById(any());
  }

  // ---- stubbing the peer reads ----

  private final UUID recipeId = UUID.randomUUID();

  private void stubPlanWithOneRecipe(
      String key, String qty, String unit, int baseServings, int generation) {
    RecipeDto recipe = recipe(recipeId, key, qty, unit, baseServings);
    when(recipeQueryService.getById(recipeId)).thenReturn(Optional.of(recipe));
    when(planQueryService.getPlanById(PLAN))
        .thenReturn(Optional.of(planWith(generation, slot(recipe, baseServings))));
  }

  private void stubPlanWithSameRecipeInThreeSlots(
      String key, String qty, String unit, int generation) {
    RecipeDto recipe = recipe(recipeId, key, qty, unit, 1);
    when(recipeQueryService.getById(recipeId)).thenReturn(Optional.of(recipe));
    when(planQueryService.getPlanById(PLAN))
        .thenReturn(
            Optional.of(planWith(generation, slot(recipe, 1), slot(recipe, 1), slot(recipe, 1))));
  }

  private static RecipeDto recipe(UUID id, String key, String qty, String unit, int baseServings) {
    IngredientDto ing =
        new IngredientDto(
            UUID.randomUUID(), 0, key, key, new BigDecimal(qty), unit, null, false, false, null);
    RecipeMetadataDto meta =
        new RecipeMetadataDto(
            baseServings, 10, 20, 30, List.of(), null, null, false, "british", List.of("dinner"));
    RecipeVersionDto body =
        new RecipeVersionDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            null,
            null,
            null,
            "ready",
            Instant.now(),
            "system",
            null,
            List.of(ing),
            List.of(),
            meta,
            null,
            null);
    return new RecipeDto(
        id,
        USER,
        null,
        key,
        null,
        1,
        body.branchId(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0L,
        Instant.now(),
        Instant.now(),
        body,
        List.of());
  }

  private static MealSlotDto slot(RecipeDto recipe, int servings) {
    ScheduledRecipeDto scheduled =
        new ScheduledRecipeDto(
            UUID.randomUUID(),
            recipe.id(),
            recipe.currentVersionBody().id(),
            recipe.currentBranchId(),
            servings,
            null,
            null,
            null,
            false);
    return new MealSlotDto(
        UUID.randomUUID(),
        0,
        com.example.mealprep.core.types.SlotKind.DINNER,
        "Dinner",
        600,
        true,
        List.of(),
        null,
        null,
        null,
        null,
        scheduled);
  }

  private static PlanDto planWith(int generation, MealSlotDto... slots) {
    DayDto day = new DayDto(UUID.randomUUID(), LocalDate.of(2026, 6, 1), null, List.of(slots));
    return new PlanDto(
        PLAN,
        null,
        LocalDate.of(2026, 6, 1),
        generation,
        null,
        PlanStatus.GENERATED,
        TriggerKind.USER_INITIATED,
        null,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(day),
        0L,
        Instant.now(),
        Instant.now());
  }

  /**
   * Supplies the peer query services as {@code @Primary} Mockito mocks. Using a config bean (not
   * {@code @MockBean}) avoids evicting the multi-interface {@code RecipeServiceImpl} / {@code
   * PlannerServiceImpl} beans' OTHER interfaces from the context.
   */
  @TestConfiguration
  static class PeerMockConfig {

    @Bean
    @Primary
    PlanQueryService testPlanQueryService() {
      return mock(PlanQueryService.class);
    }

    @Bean
    @Primary
    RecipeQueryService testRecipeQueryService() {
      return mock(RecipeQueryService.class);
    }

    @Bean
    @Primary
    ProvisionQueryService testProvisionQueryService() {
      ProvisionQueryService m = mock(ProvisionQueryService.class);
      lenient().when(m.getStaplesNeedingReplenishment(any())).thenReturn(List.of());
      lenient()
          .when(m.listActiveInventory(any(), any(), any()))
          .thenReturn(org.springframework.data.domain.Page.empty());
      return m;
    }

    @Bean
    @Primary
    LifestyleConfigQueryService testLifestyleConfigQueryService() {
      LifestyleConfigQueryService m = mock(LifestyleConfigQueryService.class);
      lenient().when(m.getLifestyleConfig(any())).thenReturn(Optional.empty());
      return m;
    }

    @Bean
    @Primary
    HouseholdQueryService testHouseholdQueryService() {
      HouseholdQueryService m = mock(HouseholdQueryService.class);
      lenient().when(m.getByUserId(any())).thenReturn(Optional.empty());
      return m;
    }
  }
}
