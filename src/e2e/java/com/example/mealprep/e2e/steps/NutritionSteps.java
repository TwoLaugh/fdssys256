package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ScenarioContext;
import com.example.mealprep.e2e.support.TestPayloads;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.Map;

/**
 * Nutrition step definitions (nutrition.md): set/read targets (NUT-01), standalone food logging
 * (NUT-19), USDA-derived ingredient nutrition (NUT-26), and an invalid-target error (NUT-05).
 *
 * <p>Self-scoped (D5): every read/assert is for THIS user (the server resolves {@code userId} from
 * the session — never from a param), and the snack/aggregate assertions look only at this user's
 * day, never a global total. External deps (USDA / Open Food Facts) are REAL in CI, so the
 * ingredient lookup keeps to a common, cleanly-mapping food ("banana") and asserts the mapping
 * shape, not specific macro numbers (±10-15% by design).
 */
public class NutritionSteps {

  private static final String SNACK_DATE = "nutrition.snackDate";

  private final ScenarioContext context;

  public NutritionSteps(ScenarioContext context) {
    this.context = context;
  }

  @When("they set their nutrition targets")
  public void theySetTheirNutritionTargets() {
    // First write: the row does not exist yet, so the expected optimistic version is 0.
    Response response = putTargets(TestPayloads.nutritionTargets(0L));
    context.setLastResponse(response);
  }

  @Then("the targets are stored and returned for this user")
  public void theTargetsAreStoredAndReturnedForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("PUT targets should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(response.jsonPath().getString("goal")).isEqualTo("MAINTAIN");
    assertThat(response.jsonPath().getInt("calories.dailyTarget")).isEqualTo(2000);
  }

  @When("they read their nutrition targets")
  public void theyReadTheirNutritionTargets() {
    context.setLastResponse(context.api().get("/api/v1/nutrition/targets"));
  }

  @Then("the targets read returns the stored targets for this user")
  public void theTargetsReadReturnsTheStoredTargetsForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("GET targets should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(response.jsonPath().getInt("calories.dailyTarget")).isEqualTo(2000);
  }

  @When("they submit nutrition targets with a negative protein target")
  public void theySubmitNutritionTargetsWithANegativeProteinTarget() {
    Map<String, Object> body = TestPayloads.nutritionTargets(0L);
    @SuppressWarnings("unchecked")
    Map<String, Object> protein = (Map<String, Object>) body.get("protein");
    protein.put("targetG", "-50.0"); // violates @DecimalMin("0.0")
    context.setLastResponse(putTargets(body));
  }

  @When("they log a standalone snack for today")
  public void theyLogAStandaloneSnackForToday() {
    LocalDate today = LocalDate.now();
    context.put(SNACK_DATE, today.toString());
    Response response =
        context
            .api()
            .post("/api/v1/nutrition/intake/" + today + "/snacks", TestPayloads.snack("almonds"));
    context.setLastResponse(response);
  }

  @Then("the snack is recorded on today's intake for this user")
  public void theSnackIsRecordedOnTodaysIntakeForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("log snack should return 201 Created").isEqualTo(201);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(response.jsonPath().getString("onDate")).isEqualTo(context.get(SNACK_DATE));
    // Self-scoped: at least this scenario's snack is on the day (never assert a global count).
    assertThat(response.jsonPath().getList("snacks")).isNotEmpty();
  }

  @When("they look up nutrition for the ingredient {string}")
  public void theyLookUpNutritionForTheIngredient(String term) {
    context.setLastResponse(context.api().get("/api/v1/nutrition/ingredients/lookup?term=" + term));
  }

  @Then("the lookup returns a mapped nutrition record for that ingredient")
  public void theLookupReturnsAMappedNutritionRecordForThatIngredient() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("ingredient lookup should return 200 OK").isEqualTo(200);
    // Shape-only: a mapped record carries a source (USDA / OPEN_FOOD_FACTS / MANUAL) and a
    // per-100g nutrition document. We do NOT assert specific macro values (external data, ±10-15%).
    assertThat(response.jsonPath().getString("source")).isIn("USDA", "OPEN_FOOD_FACTS", "MANUAL");
    assertThat(response.jsonPath().getMap("nutritionPer100g")).isNotNull();
  }

  private Response putTargets(Map<String, Object> body) {
    return context.api().request().body(body).when().put("/api/v1/nutrition/targets");
  }
}
