package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-Postgres IT for the two adaptation affected-set reads on {@link RecipeQueryService}: {@link
 * RecipeQueryService#findUserRecipeIngredientKeys(UUID)} and {@link
 * RecipeQueryService#findUserRecipeNutrition(UUID)}.
 *
 * <p>Seeds rows directly via {@link JdbcTemplate} (rather than the HTTP create path) so the test
 * can construct the full matrix the queries must filter: a multi-ingredient + nutrition recipe with
 * a superseding second version, a null-nutrition recipe, a SYSTEM-catalogue recipe, an archived and
 * a soft-deleted USER recipe, and a different user's recipe. Asserts exact map contents — that only
 * the <b>current</b> version's ingredients are returned and that scope/active filters hold.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class RecipeAffectedSetQueriesIT {

  @Autowired private RecipeQueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("UPDATE recipe_recipes SET current_branch_id = NULL");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes");
  }

  @Test
  void findUserRecipeIngredientKeys_and_findUserRecipeNutrition_scopeToActiveCurrentVersion() {
    UUID userId = UUID.randomUUID();
    UUID otherUserId = UUID.randomUUID();
    String nutritionJson = "{\"caloriesPerServing\":520,\"proteinG\":31.5}";

    // (1) Active USER recipe with a superseded v1 (2 ingredients, no nutrition) and a CURRENT v2
    // (different 2 ingredients + nutrition). Only v2's ingredients + nutrition must surface.
    UUID recipeWithNutrition = UUID.randomUUID();
    UUID branchA = UUID.randomUUID();
    seedRecipe(recipeWithNutrition, userId, "USER", branchA, /* currentVersion= */ 2, null, null);
    seedBranch(branchA, recipeWithNutrition, 2);
    UUID v1 = UUID.randomUUID();
    seedVersion(v1, recipeWithNutrition, branchA, 1, /* nutritionJson= */ null);
    seedIngredient(v1, 0, "old.key.alpha");
    seedIngredient(v1, 1, "old.key.beta");
    UUID v2 = UUID.randomUUID();
    seedVersion(v2, recipeWithNutrition, branchA, 2, nutritionJson);
    seedIngredient(v2, 0, "spaghetti.dry");
    seedIngredient(v2, 1, "beef.mince");

    // (2) Active USER recipe with ingredients but NULL nutrition_per_serving. Present in the
    // ingredient-keys map; ABSENT from the nutrition map.
    UUID recipeNoNutrition = UUID.randomUUID();
    UUID branchB = UUID.randomUUID();
    seedRecipe(recipeNoNutrition, userId, "USER", branchB, 1, null, null);
    seedBranch(branchB, recipeNoNutrition, 1);
    UUID vNoNutrition = UUID.randomUUID();
    seedVersion(vNoNutrition, recipeNoNutrition, branchB, 1, null);
    seedIngredient(vNoNutrition, 0, "tomato.passata");

    // (3) SYSTEM-catalogue recipe (ingredients + nutrition) — absent from BOTH maps.
    UUID systemRecipe = UUID.randomUUID();
    UUID branchC = UUID.randomUUID();
    seedRecipe(systemRecipe, new UUID(0L, 0L), "SYSTEM", branchC, 1, null, null);
    seedBranch(branchC, systemRecipe, 1);
    UUID vSystem = UUID.randomUUID();
    seedVersion(vSystem, systemRecipe, branchC, 1, nutritionJson);
    seedIngredient(vSystem, 0, "system.key");

    // (4) Archived USER recipe — absent from BOTH maps.
    UUID archivedRecipe = UUID.randomUUID();
    UUID branchD = UUID.randomUUID();
    seedRecipe(archivedRecipe, userId, "USER", branchD, 1, Instant.now(), null);
    seedBranch(branchD, archivedRecipe, 1);
    UUID vArchived = UUID.randomUUID();
    seedVersion(vArchived, archivedRecipe, branchD, 1, nutritionJson);
    seedIngredient(vArchived, 0, "archived.key");

    // (5) Soft-deleted USER recipe — absent from BOTH maps.
    UUID deletedRecipe = UUID.randomUUID();
    UUID branchE = UUID.randomUUID();
    seedRecipe(deletedRecipe, userId, "USER", branchE, 1, null, Instant.now());
    seedBranch(branchE, deletedRecipe, 1);
    UUID vDeleted = UUID.randomUUID();
    seedVersion(vDeleted, deletedRecipe, branchE, 1, nutritionJson);
    seedIngredient(vDeleted, 0, "deleted.key");

    // (6) A different user's active recipe — absent (user scoping).
    UUID otherUserRecipe = UUID.randomUUID();
    UUID branchF = UUID.randomUUID();
    seedRecipe(otherUserRecipe, otherUserId, "USER", branchF, 1, null, null);
    seedBranch(branchF, otherUserRecipe, 1);
    UUID vOther = UUID.randomUUID();
    seedVersion(vOther, otherUserRecipe, branchF, 1, nutritionJson);
    seedIngredient(vOther, 0, "other.user.key");

    // ---- ingredient-keys map ----
    Map<UUID, List<String>> keys = queryService.findUserRecipeIngredientKeys(userId);

    assertThat(keys).containsOnlyKeys(recipeWithNutrition, recipeNoNutrition);
    // Only the CURRENT version (v2) keys — not the superseded v1's old.key.alpha/old.key.beta.
    assertThat(keys.get(recipeWithNutrition))
        .containsExactlyInAnyOrder("spaghetti.dry", "beef.mince");
    assertThat(keys.get(recipeNoNutrition)).containsExactly("tomato.passata");

    // ---- nutrition map ----
    Map<UUID, JsonNode> nutrition = queryService.findUserRecipeNutrition(userId);

    assertThat(nutrition).containsOnlyKeys(recipeWithNutrition);
    JsonNode node = nutrition.get(recipeWithNutrition);
    assertThat(node).isNotNull();
    assertThat(node.get("caloriesPerServing").asInt()).isEqualTo(520);
    assertThat(node.get("proteinG").asDouble()).isEqualTo(31.5);
  }

  @Test
  void emptyMaps_whenUserHasNoActiveRecipes() {
    assertThat(queryService.findUserRecipeIngredientKeys(UUID.randomUUID())).isEmpty();
    assertThat(queryService.findUserRecipeNutrition(UUID.randomUUID())).isEmpty();
  }

  @Test
  void zeroIngredientRecipe_absentFromIngredientKeysMap_butNutritionStillReturned() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    seedRecipe(recipeId, userId, "USER", branchId, 1, null, null);
    seedBranch(branchId, recipeId, 1);
    UUID versionId = UUID.randomUUID();
    seedVersion(versionId, recipeId, branchId, 1, "{\"caloriesPerServing\":100}");
    // No ingredients seeded.

    assertThat(queryService.findUserRecipeIngredientKeys(userId)).isEmpty();
    assertThat(queryService.findUserRecipeNutrition(userId)).containsOnlyKeys(recipeId);
  }

  // ---------------- seed helpers ----------------

  private void seedRecipe(
      UUID id,
      UUID userId,
      String catalogue,
      UUID currentBranchId,
      int currentVersion,
      Instant archivedAt,
      Instant deletedAt) {
    // recipe_recipes.current_branch_id has an FK to recipe_branches (V…800201). Mirror the
    // production create flow: insert the recipe with a null current_branch_id first; the caller
    // then seeds the branch and the current_branch_id is back-filled in seedBranch.
    jdbcTemplate.update(
        "INSERT INTO recipe_recipes (id, user_id, catalogue, name, current_version,"
            + " current_branch_id, data_quality, nutrition_status, archived_at, deleted_at,"
            + " optimistic_version, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, NULL, 'USER_VERIFIED', 'PENDING', ?, ?, 0, now(), now())",
        id,
        userId,
        catalogue,
        "Recipe " + id,
        currentVersion,
        archivedAt == null ? null : java.sql.Timestamp.from(archivedAt),
        deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
  }

  private void seedBranch(UUID id, UUID recipeId, int currentVersion) {
    jdbcTemplate.update(
        "INSERT INTO recipe_branches (id, recipe_id, name, current_version, divergence_score,"
            + " created_at, created_by_actor, version)"
            + " VALUES (?, ?, 'main', ?, 0.000, now(), 'user:test', 0)",
        id,
        recipeId,
        currentVersion);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET current_branch_id = ? WHERE id = ?", id, recipeId);
  }

  private void seedVersion(
      UUID id, UUID recipeId, UUID branchId, int versionNumber, String nutritionJson) {
    jdbcTemplate.update(
        "INSERT INTO recipe_versions (id, recipe_id, branch_id, version_number, change_diff,"
            + " trigger, nutrition_per_serving, embedding_status, created_at, created_by_actor)"
            + " VALUES (?, ?, ?, ?, '{}'::jsonb, 'MANUAL_CREATE', ?::jsonb, 'pending', now(),"
            + " 'user:test')",
        id,
        recipeId,
        branchId,
        versionNumber,
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
