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
 * Grocery step definitions (grocery.md): the manual-fulfilment → inventory leg — the ONLY grocery
 * surface present in the running app — exercised over the black-box HTTP API (decision D2).
 *
 * <p><b>The load-bearing finding that shapes this feature.</b> There is no grocery module in {@code
 * src/main}: no shopping-list, order-lifecycle, substitution, or price-history HTTP surface exists
 * (the grocery domain is "designed-but-not-yet-built" per the pathway doc, and the list-calculation
 * ownership is a cross-doc HLD-GAP between grocery and the planner). The one buildable-green
 * grocery behaviour is the manual-fulfilment leg of the flagship (GROC-08 / the manual-only variant
 * of GROC-36): a confirmed order is imported into Provisions inventory via {@code POST
 * /api/v1/provisions/grocery-import} (200) — the same {@code addToInventory} write every fulfilment
 * path makes — idempotent on {@code (userId, supplier, orderRef)} (a replay → 409). Every other
 * tier is {@code @pending} with a precise reason in the feature.
 *
 * <p>This glue uses grocery-tier wording for its steps so it does not collide with {@code
 * ProvisionsSteps} (which exercises the same endpoint from the Provisions/PROV-03 angle). The
 * {@code @pending}-only steps target surfaces that do not yet exist; they are authored so the
 * moment a grocery module lands they become green by un-pending.
 *
 * <p>Self-contained (D5): every scenario registers its OWN fresh user and uses a per-run-random
 * {@code orderRef}; self-scoped: the import result is per-user, asserted only on THIS import's
 * added rows, never a global count.
 */
public class GrocerySteps {

  private static final String GROCERY_IMPORT = "/api/v1/provisions/grocery-import";

  /** Cross-step keys (domain-namespaced — see ScenarioContext javadoc). */
  private static final String SUPPLIER = "grocery.supplier";

  private static final String ORDER_REF = "grocery.orderRef";

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

  // ---------------- @pending glue (authored; targets surfaces that do not yet exist) ----------

  @When("they request the shopping list")
  public void theyRequestTheShoppingList() {
    // No shopping-list HTTP surface exists in the running app — see the @pending reason.
    context.setLastResponse(context.api().get("/api/v1/grocery/shopping-list"));
  }

  @Then("the shopping list lists the unmet ingredient lines for this user")
  public void theShoppingListListsTheUnmetIngredientLinesForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("shopping-list GET should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getList("lines")).isNotNull();
  }

  @When("they request the shopping list cost projection")
  public void theyRequestTheShoppingListCostProjection() {
    context.setLastResponse(context.api().get("/api/v1/grocery/shopping-list/cost-projection"));
  }

  @Then("the cost projection carries an estimate and confidence for this user")
  public void theCostProjectionCarriesAnEstimateAndConfidenceForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("cost-projection GET should return 200 OK").isEqualTo(200);
    assertThat((Object) response.jsonPath().get("estimatedTotal")).isNotNull();
    assertThat((Object) response.jsonPath().get("confidence")).isNotNull();
  }

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

  @When("they request the learned price for an ingredient")
  public void theyRequestTheLearnedPriceForAnIngredient() {
    context.setLastResponse(context.api().get("/api/v1/grocery/prices/chicken-thighs"));
  }

  @Then("the learned price carries an estimate and confidence range for this user")
  public void theLearnedPriceCarriesAnEstimateAndConfidenceRangeForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("learned-price GET should return 200 OK").isEqualTo(200);
    assertThat((Object) response.jsonPath().get("estimate")).isNotNull();
    assertThat((Object) response.jsonPath().get("confidence")).isNotNull();
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
