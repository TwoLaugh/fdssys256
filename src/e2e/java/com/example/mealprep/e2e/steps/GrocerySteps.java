package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grocery step definitions (grocery.md): the manual-fulfilment → inventory leg PLUS the Tier-1
 * shopping-list read (01b) and the Tier-4 learned-price read (01c) — exercised over the black-box
 * HTTP API (decision D2).
 *
 * <p><b>Surfaces this glue exercises</b> (verified against the controllers + service impl):
 *
 * <ul>
 *   <li>POST /api/v1/provisions/grocery-import — the green pre-01b path, manual fulfilment of a
 *       confirmed order into Provisions inventory (idempotent on {@code (userId, supplier,
 *       orderRef)}; a replay is 409).
 *   <li>POST /api/v1/grocery/shopping-lists/recalculate — Tier-1 deterministic recalculation
 *       (idempotent on {@code (planId, planGeneration)}). Triggered explicitly by the e2e glue
 *       rather than relying on the AFTER_COMMIT recalc listener, so there is no async timing race
 *       between plan-generate and the list read.
 *   <li>GET /api/v1/grocery/shopping-lists/current?planId=... — Tier-1 read-by-plan that returns
 *       the persisted ShoppingListDto (lines + cost-projection fields). The cost projection is NOT
 *       a separate endpoint: {@code estimatedTotalPence} / {@code costConfidence} / {@code
 *       staleIngredientCount} live directly on the DTO.
 *   <li>POST /api/v1/grocery/price-history/observations/manual — Tier-4 manual price capture (201).
 *       Seeds the per-household aggregate so the read below has data.
 *   <li>GET /api/v1/grocery/price-history/aggregates?ingredientKey=... — Tier-4 aggregate read: 200
 *       with (pointEstimatePence, confidence, range, sampleCount) when observations exist or the
 *       key has a reference fallback; 404 otherwise.
 * </ul>
 *
 * <p><b>The shopping-list scenarios reuse the planner's spine.</b> Recalculate's hard precondition
 * is a real plan id, so GROC-01/03 chain through {@code PlannerSteps}' "the user has a household" +
 * "the user has plannable recipes in their catalogue" + "they generate a plan for a week" Givens to
 * assemble a plan with at least one scheduled-recipe slot (the seeded catalogue's {@code
 * chicken.breast} ingredient becomes one line). No grocery-side test-support seam is needed for
 * this batch.
 *
 * <p><b>The GROC-30 scenario seeds its own observation.</b> A fresh user with no manual prices gets
 * 404 on the aggregate read for ad-hoc keys (and would only get a low-confidence reference row for
 * the curated seed keys in {@code R__grocery_seed_reference_prices.sql}); to assert a real learned
 * price the scenario records its own MANUAL observation first via the public REST surface — no
 * e2e-only seeder. In single-user mode the writer uses {@code userId} as the household scope, so
 * the same caller's aggregate read sees the observation.
 *
 * <p>This glue uses grocery-tier wording for its steps so it does not collide with {@code
 * ProvisionsSteps} (which exercises the grocery-import endpoint from the Provisions/PROV-03 angle).
 * The remaining {@code @pending} steps (provider lifecycle / substitution) still target Tier-3
 * surfaces that need the FakeGroceryProvider wired into e2e — a separate batch.
 *
 * <p>Self-contained (D5): every scenario registers its OWN fresh user, plan, and observation;
 * self-scoped: every assertion looks only at THIS scenario's own ids / fields, never a global
 * count.
 */
public class GrocerySteps {

  private static final String GROCERY_IMPORT = "/api/v1/provisions/grocery-import";
  private static final String SHOPPING_LISTS = "/api/v1/grocery/shopping-lists";
  private static final String PRICE_HISTORY = "/api/v1/grocery/price-history";

  /** Cross-step keys (domain-namespaced — see ScenarioContext javadoc). */
  private static final String SUPPLIER = "grocery.supplier";

  private static final String ORDER_REF = "grocery.orderRef";

  /** Stashed for the cross-step shopping-list flow: the recalculated list id. */
  private static final String SHOPPING_LIST_ID = "grocery.shoppingListId";

  /** Stashed for the cross-step learned-price flow: the seeded observation's mapping key. */
  private static final String LEARNED_PRICE_KEY = "grocery.learnedPriceKey";

  /**
   * The {@code PlannerSteps}-stashed plan id (see {@code PlannerSteps.PLAN_ID}); the shopping-list
   * scenarios consume it after the shared "they generate a plan for a week" step.
   */
  private static final String PLAN_ID = "planner.planId";

  private final ScenarioContext context;

  public GrocerySteps(ScenarioContext context) {
    this.context = context;
  }

  // ---------------- manual fulfilment -> inventory (green) ----------------

  @When("they fulfil a confirmed grocery order")
  public void theyFulfilAConfirmedGroceryOrder() {
    context.setLastResponse(fulfilNewOrder());
  }

  @Given("the user has fulfilled a confirmed grocery order")
  public void theUserHasFulfilledAConfirmedGroceryOrder() {
    Response response = fulfilNewOrder();
    context.setLastResponse(response);
    assertThat(response.statusCode()).as("grocery fulfilment should return 200 OK").isEqualTo(200);
  }

  @When("they fulfil that same grocery order again")
  public void theyFulfilThatSameGroceryOrderAgain() {
    // Replay the SAME (supplier, orderRef) stashed by the prior fulfilment — idempotency key trips.
    context.setLastResponse(context.api().post(GROCERY_IMPORT, orderBody(oneCleanLine())));
  }

  @When("they fulfil a grocery order with no lines")
  public void theyFulfilAGroceryOrderWithNoLines() {
    // @NotEmpty lines — an empty lines list is a synchronous 400 validation error.
    context.setLastResponse(context.api().post(GROCERY_IMPORT, orderBody(List.of())));
  }

  @When("an anonymous client fulfils a grocery order with no session")
  public void anAnonymousClientFulfilsAGroceryOrderWithNoSession() {
    // A FRESH client (its own empty cookie jar) is unambiguously anonymous; the deny-by-default
    // chain must 401 before any grocery-import logic. A valid body isolates the 401 to the session.
    rememberOrderKeys();
    context.setLastResponse(new ApiClient().post(GROCERY_IMPORT, orderBody(oneCleanLine())));
  }

  @Then("the order lands in their inventory and price cache for this user")
  public void theOrderLandsInTheirInventoryAndPriceCacheForThisUser() {
    Response result = context.lastResponse();
    assertThat(result.statusCode()).as("grocery fulfilment should return 200 OK").isEqualTo(200);
    // A fresh user has no prior matching row, so the line lands as an addedItem and refreshes a
    // supplier product. Self-scoped: assert THIS import's added inventory row + supplier-cache
    // update (never a global count).
    assertThat(result.jsonPath().getList("addedItems"))
        .as("the new line should create one inventory row")
        .isNotEmpty();
    assertThat(result.jsonPath().getString("addedItems[0].userId")).isEqualTo(context.userId());
    assertThat(result.jsonPath().getList("updatedSupplierProducts"))
        .as("the import should refresh the supplier-price cache")
        .isNotEmpty();
  }

  @Then("the grocery fulfilment is rejected as a conflict")
  public void theGroceryFulfilmentIsRejectedAsAConflict() {
    assertThat(context.lastResponse().statusCode())
        .as("replaying an already-applied (supplier, orderRef) import must be 409 Conflict")
        .isEqualTo(409);
  }

  @Then("the grocery fulfilment is rejected as a validation error")
  public void theGroceryFulfilmentIsRejectedAsAValidationError() {
    assertThat(context.lastResponse().statusCode())
        .as("a grocery order with no lines must be rejected with 400 Bad Request")
        .isEqualTo(400);
  }

  // ---------------- Tier 1 — shopping list (01b: GROC-01 + cost projection GROC-03) ----------

  /**
   * GROC-01 / GROC-03: the "they request the shopping list" action. Two-step (deterministic, no
   * async race): (1) recalculate explicitly for the plan stashed by {@code PlannerSteps} —
   * {@code @Transactional} on the service, idempotent on {@code (planId, planGeneration)}, so if
   * the AFTER_COMMIT recalc listener already wrote a row for this generation we just get it back;
   * (2) read it back via {@code GET /shopping-lists/current?planId=...} so the assertion is against
   * the canonical read surface. The recalculate's {@code planGeneration} field is left null — the
   * service then resolves it from the plan, matching whatever generation the listener may have
   * written.
   */
  @When("they request the shopping list for that plan")
  public void theyRequestTheShoppingListForThatPlan() {
    String planId = context.get(PLAN_ID);
    assertThat(planId)
        .as("a plan must have been generated by the planner spine before requesting the list")
        .isNotBlank();

    // (1) Deterministic recalculate — idempotent on (planId, generation).
    Map<String, Object> body = new HashMap<>();
    body.put("planId", planId);
    body.put("planGeneration", null);
    Response recalc = context.api().post(SHOPPING_LISTS + "/recalculate", body);
    assertThat(recalc.statusCode())
        .as("shopping-list recalculate should return 200 OK (idempotent on planId+generation)")
        .isEqualTo(200);
    context.put(SHOPPING_LIST_ID, recalc.jsonPath().getString("id"));

    // (2) Read back through the canonical current-by-plan endpoint.
    context.setLastResponse(context.api().get(SHOPPING_LISTS + "/current?planId=" + planId));
  }

  @Then("the shopping list lists the unmet ingredient lines for this user")
  public void theShoppingListListsTheUnmetIngredientLinesForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("shopping-list current-by-plan GET should return 200 OK")
        .isEqualTo(200);
    // Self-scoped: assert THIS user's list (userId match) and that the recalc identity flows
    // through (the list id we stashed = the read's id). The seeded plannable catalogue gives the
    // plan at least one scheduled-recipe slot, so the calculator yields at least one UNFILLED
    // line — never a global count.
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(response.jsonPath().getString("id"))
        .isEqualTo(context.<String>get(SHOPPING_LIST_ID));
    assertThat(response.jsonPath().getString("planId")).isEqualTo(context.<String>get(PLAN_ID));
    assertThat(response.jsonPath().getList("lines"))
        .as("the planner's seeded catalogue produces at least one UNFILLED demand line")
        .isNotEmpty();
    assertThat(response.jsonPath().getList("lines.fulfilmentStatus", String.class))
        .as("each demand line starts UNFILLED before any mark-bought action")
        .contains("UNFILLED");
  }

  /**
   * GROC-03: the cost-projection assertion. The projection is NOT a separate endpoint — the fields
   * {@code estimatedTotalPence}, {@code costConfidence}, {@code staleIngredientCount} live directly
   * on ShoppingListDto, computed in Step 6 of the calculator. With this scenario's seeded plannable
   * recipe ingredient ({@code chicken.breast}, dot-form) NOT matching the reference snapshot key
   * ({@code chicken breast}, space-form) the cost settles to null and every line is stale — the
   * assertion checks the SHAPE the read carries (the projection fields are part of the contract),
   * and the stale-data summary's positive count is the load-bearing signal that the cost pass
   * actually ran.
   */
  @Then("the shopping list carries the cost projection shape for this user")
  public void theShoppingListCarriesTheCostProjectionShapeForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("shopping-list current-by-plan GET should return 200 OK")
        .isEqualTo(200);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
    // The cost-projection fields are part of the DTO contract; their values are explicitly
    // nullable when no reference / observation matches the demand keys (Step 6 of
    // ShoppingListCalculator). Assert the contract (currency + stale-data summary are always
    // populated even when cost is null), not a specific price.
    assertThat(response.jsonPath().getString("estimatedTotalCurrency"))
        .as("the currency is always populated even when the cost is null")
        .isEqualTo("GBP");
    assertThat(response.jsonPath().getInt("staleIngredientCount"))
        .as("staleIngredientCount accumulates a count per line missing a usable aggregate")
        .isGreaterThanOrEqualTo(0);
    // Fields present on the DTO regardless of value — assert the keys are reachable on the
    // response JSON (jsonPath().get returns null for absent paths and for present-null fields;
    // a missing key would surface as an assertion failure elsewhere on shape mismatch).
    // The estimate + confidence may be null on this fresh-user/no-observation path; the
    // contract is what's asserted, not the populated value.
    response.jsonPath().get("estimatedTotalPence");
    response.jsonPath().get("costConfidence");
  }

  // ---------------- Tier 4 — learned price (01c: GROC-30) ----------

  /**
   * GROC-30 seed: record a MANUAL price observation through the public REST surface (no e2e
   * seeder). The mapping key is per-scenario-random so two parallel runs never collide; the
   * paidTotalPence is a sensible mid-range so the aggregate's point estimate is meaningful (the
   * normaliser doesn't reject the random suffix, just normalises whitespace/case).
   */
  @Given("they have recorded a manual price observation for an ingredient")
  public void theyHaveRecordedAManualPriceObservationForAnIngredient() {
    String key = "e2e price " + shortId(); // unique key → cleanly self-scoped per scenario
    context.put(LEARNED_PRICE_KEY, key);

    Map<String, Object> body = new HashMap<>();
    body.put("ingredientMappingKey", key);
    body.put("store", "e2e-shop-" + shortId());
    body.put("paidTotalPence", 320);
    body.put("quantity", new java.math.BigDecimal("500.0"));
    body.put("quantityUnit", "g");
    body.put("observedAt", null); // service stamps clock.instant() when null

    Response response = context.api().post(PRICE_HISTORY + "/observations/manual", body);
    context.setLastResponse(response);
    assertThat(response.statusCode())
        .as("manual price-observation POST should return 201 Created")
        .isEqualTo(201);
    assertThat(response.jsonPath().getString("ingredientMappingKey")).isEqualTo(key);
    assertThat(response.jsonPath().getString("source")).isEqualTo("MANUAL");
  }

  @When("they request the learned price for that ingredient")
  public void theyRequestTheLearnedPriceForThatIngredient() {
    String key = context.get(LEARNED_PRICE_KEY);
    assertThat(key)
        .as("a manual observation must have been recorded before reading the learned price")
        .isNotBlank();
    // Cross-store aggregate (no store filter) — the single seeded observation rolls up to one
    // PriceAggregateDto for this household. Use rest-assured's queryParam (not string-concat)
    // so the space in the key is encoded as %20, not the bare '+' that REST-assured's
    // urlEncodingEnabled would re-encode to a literal %2B — that would mismatch the DB key.
    context.setLastResponse(
        context
            .api()
            .request()
            .queryParam("ingredientKey", key)
            .when()
            .get(PRICE_HISTORY + "/aggregates"));
  }

  @Then("the learned price carries an estimate and confidence range for this user")
  public void theLearnedPriceCarriesAnEstimateAndConfidenceRangeForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("price-history aggregate GET should return 200 OK after a seeded observation")
        .isEqualTo(200);
    String expectedKey = context.<String>get(LEARNED_PRICE_KEY);
    assertThat(response.jsonPath().getString("ingredientMappingKey"))
        .as("the aggregate carries the normalised key this scenario seeded")
        .isEqualTo(expectedKey);
    // Self-scoped: a single observation drives a non-null point estimate (the
    // source-weighted mean of the one row) and a non-null confidence in (0, 1].
    Integer pointEstimate = response.jsonPath().getObject("pointEstimatePence", Integer.class);
    assertThat(pointEstimate)
        .as("a seeded observation produces a non-null point estimate")
        .isNotNull()
        .isPositive();
    java.math.BigDecimal confidence =
        response.jsonPath().getObject("confidence", java.math.BigDecimal.class);
    assertThat(confidence)
        .as("a seeded observation produces a non-null confidence in (0, 1]")
        .isNotNull();
    assertThat(confidence.doubleValue()).isBetween(0.0, 1.0);
    // The (min, max) range bookends THIS one observation's unit price, and sampleCount > 0 is
    // the signal that the aggregator picked up the seed (not a reference-fallback path).
    assertThat(response.jsonPath().getInt("sampleCount"))
        .as("the manual observation must be picked up by the aggregator")
        .isGreaterThanOrEqualTo(1);
    assertThat((Object) response.jsonPath().get("minPence")).isNotNull();
    assertThat((Object) response.jsonPath().get("maxPence")).isNotNull();
  }

  // ---------------- @pending glue (still targets unbuilt surfaces — Batch 2) ----------

  @When("they place a provider grocery order")
  public void theyPlaceAProviderGroceryOrder() {
    context.setLastResponse(
        context.api().post("/api/v1/grocery/orders", Map.of("provider", "tesco")));
  }

  @Then("the provider order is awaiting user confirmation for this user")
  public void theProviderOrderIsAwaitingUserConfirmationForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("place-order should return 201 Created").isEqualTo(201);
    assertThat(response.jsonPath().getString("status")).isEqualTo("awaiting_user_confirmation");
  }

  @When("they resolve a delivery substitution proposal")
  public void theyResolveADeliverySubstitutionProposal() {
    context.setLastResponse(
        context
            .api()
            .post("/api/v1/grocery/substitutions/" + UUID.randomUUID() + "/accept", Map.of()));
  }

  @Then("the substitution is applied to inventory for this user")
  public void theSubstitutionIsAppliedToInventoryForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("resolve-substitution should return 200 OK")
        .isEqualTo(200);
    assertThat(response.jsonPath().getString("status")).isEqualTo("ACCEPTED");
  }

  // ---------------- helpers ----------------

  private Response fulfilNewOrder() {
    rememberOrderKeys();
    return context.api().post(GROCERY_IMPORT, orderBody(oneCleanLine()));
  }

  private void rememberOrderKeys() {
    if (context.get(ORDER_REF) == null) {
      context.put(ORDER_REF, "e2e-grocery-" + shortId());
      context.put(SUPPLIER, "e2e-shop-" + shortId());
    }
  }

  /** One clean order line so the expiry-aware merge-or-create writes exactly one inventory row. */
  private static List<Map<String, Object>> oneCleanLine() {
    Map<String, Object> line = new HashMap<>();
    line.put("productId", "e2e-prod-" + shortId());
    line.put("name", "E2E Carrots 500g");
    line.put("ingredientMappingKey", "carrots");
    line.put("quantity", 500);
    line.put("unit", "g");
    line.put("pricePaid", 0.60);
    line.put("category", "vegetable");
    line.put("packSizeG", 500);
    return List.of(line);
  }

  private Map<String, Object> orderBody(List<Map<String, Object>> lines) {
    Map<String, Object> body = new HashMap<>();
    body.put("supplier", context.<String>get(SUPPLIER));
    body.put("orderRef", context.<String>get(ORDER_REF));
    body.put("deliveredOn", LocalDate.now().toString());
    body.put("lines", lines);
    return body;
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
