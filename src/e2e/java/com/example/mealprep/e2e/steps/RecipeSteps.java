package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ScenarioContext;
import com.example.mealprep.e2e.support.TestPayloads;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;

/**
 * Recipe step definitions (recipe.md): manual create (RCP-01), read by id (RCP-11), manual edit ->
 * new version (RCP-19/RCP-20), quick rating (RCP-58), URL import (RCP-03/RCP-04), plus the
 * validation/not-found errors (RCP-02/RCP-12).
 *
 * <p>Self-scoped (D5): every assertion is on THIS scenario's recipe id, branch id and version
 * number — stashed in the {@link ScenarioContext} attribute bag under {@code recipe.*} keys so the
 * edit / rating / read steps that follow a create can find them. No global recipe counts.
 *
 * <p>AI is a deterministic double in the e2e stack, so assertions target the PLUMBING (was a
 * version created, what trigger, what data-quality/nutrition status) — never AI-generated content.
 */
public class RecipeSteps {

  private static final String RECIPE_ID = "recipe.id";
  private static final String RECIPE_NAME = "recipe.name";
  private static final String RECIPE_BRANCH_ID = "recipe.branchId";
  private static final String RECIPE_VERSION_BODY_ID = "recipe.currentVersionBodyId";
  private static final String RECIPE_OPTIMISTIC_VERSION = "recipe.optimisticVersion";

  private final ScenarioContext context;

  public RecipeSteps(ScenarioContext context) {
    this.context = context;
  }

  @When("they create a manual recipe")
  public void theyCreateAManualRecipe() {
    String name = "E2E Bolognese " + UUID.randomUUID().toString().substring(0, 8);
    context.put(RECIPE_NAME, name);
    Response response = context.api().post("/api/v1/recipes", TestPayloads.manualRecipe(name));
    context.setLastResponse(response);
  }

  @Then("the recipe is created in their user catalogue at version 1")
  public void theRecipeIsCreatedInTheirUserCatalogueAtVersion1() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("manual create should return 201 Created").isEqualTo(201);
    assertThat(response.jsonPath().getString("name")).isEqualTo(context.get(RECIPE_NAME));
    assertThat(response.jsonPath().getString("catalogue")).isEqualTo("USER");
    assertThat(response.jsonPath().getInt("currentVersion")).isEqualTo(1);
    rememberRecipeFrom(response);
  }

  @When("they read that recipe by id")
  public void theyReadThatRecipeById() {
    Response response = context.api().get("/api/v1/recipes/" + recipeId());
    context.setLastResponse(response);
  }

  @Then("the recipe read returns the same recipe with its current version body")
  public void theRecipeReadReturnsTheSameRecipeWithItsCurrentVersionBody() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("read by id should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("id")).isEqualTo(recipeId());
    assertThat(response.jsonPath().getString("currentVersionBody.id")).isNotBlank();
  }

  @When("they manually edit that recipe")
  public void theyManuallyEditThatRecipe() {
    long expected = context.<Long>get(RECIPE_OPTIMISTIC_VERSION);
    Response response =
        context
            .api()
            .request()
            .body(TestPayloads.manualEdit(context.get(RECIPE_NAME), expected))
            .when()
            .put("/api/v1/recipes/" + recipeId());
    context.setLastResponse(response);
  }

  @Then("a new version 2 is created with the manual-edit trigger and a change reason")
  public void aNewVersion2IsCreatedWithTheManualEditTriggerAndAChangeReason() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("manual edit should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getInt("currentVersion")).isEqualTo(2);
    assertThat(response.jsonPath().getInt("currentVersionBody.versionNumber")).isEqualTo(2);
    assertThat(response.jsonPath().getString("currentVersionBody.trigger"))
        .isEqualTo("MANUAL_EDIT");
    assertThat(response.jsonPath().getString("currentVersionBody.changeReason")).isNotBlank();
    assertThat(response.jsonPath().getString("currentVersionBody.parentVersionId")).isNotBlank();
    rememberRecipeFrom(response);
  }

  @And("the recipe's current version body reflects the edit and a recalculated nutrition status")
  public void theRecipesCurrentVersionBodyReflectsTheEditAndARecalculatedNutritionStatus() {
    Response response = context.lastResponse();
    // The edit created v2; nutrition status is recomputed by the engine. With the AI double we
    // assert it carries one of the known engine states, never a specific number (±10-15% by
    // design, and AI content is faked) — the plumbing-not-content rule.
    assertThat(response.jsonPath().getString("nutritionStatus"))
        .isIn("CALCULATED", "PARTIAL", "PENDING");
  }

  @When("they give a quick taste rating on the current version")
  public void theyGiveAQuickTasteRatingOnTheCurrentVersion() {
    Map<String, Object> body =
        Map.of("versionId", context.<String>get(RECIPE_VERSION_BODY_ID), "taste", 80);
    Response response = context.api().post("/api/v1/recipes/" + recipeId() + "/ratings", body);
    context.setLastResponse(response);
  }

  @Then("the rating is recorded against that version")
  public void theRatingIsRecordedAgainstThatVersion() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("rating create should return 201 Created").isEqualTo(201);
    assertThat(response.jsonPath().getString("id")).isNotBlank();
  }

  @When("they submit a manual recipe with a blank name")
  public void theySubmitAManualRecipeWithABlankName() {
    Map<String, Object> body = TestPayloads.manualRecipe("placeholder");
    body.put("name", "");
    context.setLastResponse(context.api().post("/api/v1/recipes", body));
  }

  @When("they submit a manual recipe with no ingredients")
  public void theySubmitAManualRecipeWithNoIngredients() {
    Map<String, Object> body = TestPayloads.manualRecipe("E2E No Ingredients");
    body.put("ingredients", java.util.List.of());
    context.setLastResponse(context.api().post("/api/v1/recipes", body));
  }

  @When("they read a recipe by a random non-existent id")
  public void theyReadARecipeByARandomNonExistentId() {
    context.setLastResponse(context.api().get("/api/v1/recipes/" + UUID.randomUUID()));
  }

  @Then("the recipe read is rejected as not found")
  public void theRecipeReadIsRejectedAsNotFound() {
    assertThat(context.lastResponse().statusCode())
        .as("a read of an unknown recipe id must be 404 Not Found")
        .isEqualTo(404);
  }

  @When("they import a recipe from an unreachable URL")
  public void theyImportARecipeFromAnUnreachableUrl() {
    // Format-valid (passes @URL bean validation) but a non-resolvable host, so the fetch fails and
    // the importer fails fast — no catalogue write. RFC 6761 reserves .invalid as never-resolving.
    Map<String, Object> body =
        Map.of("url", "https://e2e-" + UUID.randomUUID() + ".invalid/recipe");
    context.setLastResponse(context.api().post("/api/v1/recipes/imports/url", body));
  }

  @Then("the import is rejected as an import failure")
  public void theImportIsRejectedAsAnImportFailure() {
    // The importer maps both fetch failures and unparseable pages to 422 recipe-import-failure
    // (see RecipeExceptionHandler) and writes nothing — the fail-fast / no-partial-write contract.
    assertThat(context.lastResponse().statusCode())
        .as("an unreachable/unimportable URL must be rejected with 422 Unprocessable Entity")
        .isEqualTo(422);
  }

  @When("they import a recipe from a reachable recipe URL")
  public void theyImportARecipeFromAReachableRecipeUrl() {
    // The reachable URL is provisioned per-environment; scenarios using this step are @pending
    // until a stable whitelisted recipe URL fixture exists in CI (the fetch + JSON-LD extraction
    // are real, not the AI double). Read from config so a later wave only swaps the value.
    String url = System.getProperty("mealprep.e2e.recipe-url", "https://example.com/recipe");
    context.setLastResponse(context.api().post("/api/v1/recipes/imports/url", Map.of("url", url)));
  }

  @Then("the recipe is imported into their user catalogue with imported data quality")
  public void theRecipeIsImportedIntoTheirUserCatalogueWithImportedDataQuality() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("URL import should return 201 Created").isEqualTo(201);
    assertThat(response.jsonPath().getString("dataQuality")).isEqualTo("IMPORTED");
    assertThat(response.jsonPath().getString("catalogue")).isEqualTo("USER");
    rememberRecipeFrom(response);
  }

  @And("the imported recipe has internally derived nutrition status")
  public void theImportedRecipeHasInternallyDerivedNutritionStatus() {
    // External nutrition is discarded; the engine derives it from USDA. Assert the status exists
    // and is one of the known states (shape, not numbers — external/AI content is not asserted).
    assertThat(context.lastResponse().jsonPath().getString("nutritionStatus"))
        .isIn("CALCULATED", "PARTIAL", "PENDING");
  }

  private void rememberRecipeFrom(Response response) {
    context.put(RECIPE_ID, response.jsonPath().getString("id"));
    context.put(RECIPE_BRANCH_ID, response.jsonPath().getString("currentBranchId"));
    context.put(RECIPE_VERSION_BODY_ID, response.jsonPath().getString("currentVersionBody.id"));
    context.put(RECIPE_OPTIMISTIC_VERSION, response.jsonPath().getLong("optimisticVersion"));
  }

  private String recipeId() {
    return context.get(RECIPE_ID);
  }
}
