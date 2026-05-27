package com.example.mealprep.adaptation.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import com.example.mealprep.provisions.api.dto.PriceSensitivity;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-Postgres IT for Trigger-3's affected-recipe filters on {@link AdaptationDataModelListener}.
 * Lives in the listener's package so it can drive the (package-private) {@code
 * filterAffectedHardConstraints} / {@code filterAffectedNutrition} / {@code filterAffectedBudget}
 * methods directly against the REAL cross-module beans ({@code RecipeQueryService}, {@code
 * HardConstraintFilterService}, {@code NutritionQueryService}) with seeded rows — the
 * {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} entry points are awkward
 * to drive deterministically, so the filter methods are the unit of behaviour under test.
 *
 * <p>Recipe rows are seeded via {@link JdbcTemplate} (mirroring {@code
 * RecipeAffectedSetQueriesIT}'s proven SQL); hard-constraints and nutrition-targets aggregates are
 * seeded via their repositories using the existing {@link HardConstraintsTestData} / {@link
 * NutritionTestData} builders.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationDataModelFilterIT {

  @Autowired private AdaptationDataModelListener listener;
  @Autowired private HardConstraintsRepository hardConstraintsRepository;
  @Autowired private NutritionTargetsRepository nutritionTargetsRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("UPDATE recipe_recipes SET current_branch_id = NULL");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes");
    hardConstraintsRepository.deleteAllInBatch();
    jdbcTemplate.update("DELETE FROM nutrition_targets");
  }

  // ---------------- hard-constraints dimension ----------------

  @Test
  void filterAffectedHardConstraints_returnsOnlyTheRecipeThatViolatesAllergy() {
    UUID userId = UUID.randomUUID();
    // A direct "peanut" allergy: the simplest real violation family — no derivative-table lookup
    // needed (HardConstraintFilterServiceImpl matches the key against directAllergies first).
    hardConstraintsRepository.saveAndFlush(
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build());

    // Recipe A: current version carries the "peanut" ingredient key -> VIOLATES.
    UUID recipeA = seedActiveUserRecipe(userId, "peanut", "rice");
    // Recipe B: clean -> passes.
    UUID recipeB = seedActiveUserRecipe(userId, "rice", "chicken");

    Set<UUID> affected =
        listener.filterAffectedHardConstraints(hardConstraintsEvent(userId, "allergies"));

    assertThat(affected).containsExactly(recipeA);
    assertThat(affected).doesNotContain(recipeB);
  }

  @Test
  void filterAffectedHardConstraints_noConstraintsAggregate_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    // Recipes exist but the user has NO hard-constraints row: filterRecipes treats this as
    // "everything passes", so affected = empty.
    seedActiveUserRecipe(userId, "peanut", "rice");
    seedActiveUserRecipe(userId, "rice");

    Set<UUID> affected =
        listener.filterAffectedHardConstraints(hardConstraintsEvent(userId, "allergies"));

    assertThat(affected).isEmpty();
  }

  @Test
  void filterAffectedHardConstraints_noRecipes_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    hardConstraintsRepository.saveAndFlush(
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build());

    Set<UUID> affected =
        listener.filterAffectedHardConstraints(hardConstraintsEvent(userId, "allergies"));

    assertThat(affected).isEmpty();
  }

  // ---------------- nutrition dimension ----------------

  @Test
  void filterAffectedNutrition_returnsOnlyTheRecipeThatViolatesCalorieCeiling() {
    UUID userId = UUID.randomUUID();
    // Largest per-meal slot DINNER=700 of dailyCalorieTarget 2000 -> mealShare 0.35 -> calorie
    // allowance 700 + tolerance (0.35*150=52.5) = 752.5 ceiling.
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withPerMeal(MealSlot.BREAKFAST, 500, BigDecimal.valueOf(30.0))
            .withPerMeal(MealSlot.LUNCH, 600, BigDecimal.valueOf(40.0))
            .withPerMeal(MealSlot.DINNER, 700, BigDecimal.valueOf(40.0))
            .build();
    nutritionTargetsRepository.saveAndFlush(targets);

    // Recipe C: 1500 kcal/serving -> far above the 752.5 ceiling -> VIOLATES.
    UUID recipeC = seedActiveUserRecipeWithNutrition(userId, "{\"caloriesPerServing\":1500}");
    // Recipe D: 500 kcal/serving -> within the ceiling -> passes.
    UUID recipeD = seedActiveUserRecipeWithNutrition(userId, "{\"caloriesPerServing\":500}");

    Set<UUID> affected = listener.filterAffectedNutrition(nutritionEvent(userId));

    assertThat(affected).containsExactly(recipeC);
    assertThat(affected).doesNotContain(recipeD);
  }

  @Test
  void filterAffectedNutrition_noTargetsRow_returnsEmpty() {
    UUID userId = UUID.randomUUID();
    // A clearly-over recipe, but no targets -> nothing to violate.
    seedActiveUserRecipeWithNutrition(userId, "{\"caloriesPerServing\":9999}");

    Set<UUID> affected = listener.filterAffectedNutrition(nutritionEvent(userId));

    assertThat(affected).isEmpty();
  }

  // ---------------- budget dimension (deliberately stubbed) ----------------

  @Test
  void filterAffectedBudget_isAlwaysEmpty_documentingTheDeferral() {
    UUID userId = UUID.randomUUID();
    seedActiveUserRecipe(userId, "rice", "chicken");

    BudgetChangedEvent event =
        new BudgetChangedEvent(
            userId,
            BigDecimal.valueOf(80.00),
            BigDecimal.valueOf(50.00),
            PriceSensitivity.moderate,
            UUID.randomUUID(),
            Instant.now());

    assertThat(listener.filterAffectedBudget(event)).isEmpty();
  }

  // ---------------- event factories ----------------

  private static HardConstraintsUpdatedEvent hardConstraintsEvent(UUID userId, String field) {
    return new HardConstraintsUpdatedEvent(userId, Set.of(field), UUID.randomUUID(), Instant.now());
  }

  private static NutritionTargetsChangedEvent nutritionEvent(UUID userId) {
    return new NutritionTargetsChangedEvent(
        userId, UUID.randomUUID(), Set.of("calorieTarget"), UUID.randomUUID(), Instant.now());
  }

  // ---------------- recipe seed helpers (mirror RecipeAffectedSetQueriesIT) ----------------

  /** Seeds an active USER recipe whose single current version carries {@code ingredientKeys}. */
  private UUID seedActiveUserRecipe(UUID userId, String... ingredientKeys) {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    seedRecipe(recipeId, userId);
    seedBranch(branchId, recipeId);
    UUID versionId = UUID.randomUUID();
    seedVersion(versionId, recipeId, branchId, null);
    int line = 0;
    for (String key : ingredientKeys) {
      seedIngredient(versionId, line++, key);
    }
    return recipeId;
  }

  /** Seeds an active USER recipe whose single current version stores {@code nutritionJson}. */
  private UUID seedActiveUserRecipeWithNutrition(UUID userId, String nutritionJson) {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    seedRecipe(recipeId, userId);
    seedBranch(branchId, recipeId);
    UUID versionId = UUID.randomUUID();
    seedVersion(versionId, recipeId, branchId, nutritionJson);
    return recipeId;
  }

  private void seedRecipe(UUID id, UUID userId) {
    // current_branch_id has an FK to recipe_branches; insert null first, back-fill in seedBranch.
    jdbcTemplate.update(
        "INSERT INTO recipe_recipes (id, user_id, catalogue, name, current_version,"
            + " current_branch_id, data_quality, nutrition_status, archived_at, deleted_at,"
            + " optimistic_version, created_at, updated_at)"
            + " VALUES (?, ?, 'USER', ?, 1, NULL, 'USER_VERIFIED', 'PENDING', NULL, NULL, 0, now(),"
            + " now())",
        id,
        userId,
        "Recipe " + id);
  }

  private void seedBranch(UUID id, UUID recipeId) {
    jdbcTemplate.update(
        "INSERT INTO recipe_branches (id, recipe_id, name, current_version, divergence_score,"
            + " created_at, created_by_actor, version)"
            + " VALUES (?, ?, 'main', 1, 0.000, now(), 'user:test', 0)",
        id,
        recipeId);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET current_branch_id = ? WHERE id = ?", id, recipeId);
  }

  private void seedVersion(UUID id, UUID recipeId, UUID branchId, String nutritionJson) {
    jdbcTemplate.update(
        "INSERT INTO recipe_versions (id, recipe_id, branch_id, version_number, change_diff,"
            + " trigger, nutrition_per_serving, embedding_status, created_at, created_by_actor)"
            + " VALUES (?, ?, ?, 1, '{}'::jsonb, 'MANUAL_CREATE', ?::jsonb, 'pending', now(),"
            + " 'user:test')",
        id,
        recipeId,
        branchId,
        nutritionJson);
  }

  private void seedIngredient(UUID versionId, int lineOrder, String mappingKey) {
    jdbcTemplate.update(
        "INSERT INTO recipe_ingredients (id, version_id, line_order, ingredient_mapping_key,"
            + " display_name, optional, needs_review)"
            + " VALUES (?, ?, ?, ?, ?, false, false)",
        UUID.randomUUID(),
        versionId,
        lineOrder,
        mappingKey,
        "Display " + mappingKey);
  }
}
