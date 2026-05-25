package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Provisions step definitions (provisions.md): the physical-world state model exercised over the
 * black-box HTTP API (decision D2). Provisions is the high-value, low-risk half of Batch 2 — it has
 * real POST create paths, so the inventory create→read→update→soft-delete lifecycle (PROV-01/06/07/
 * 12/15), the waste log + history + summary (PROV-19/22), and supplier-products upsert + search
 * (PROV-33/29) are all buildable GREEN over HTTP from a fresh user, with no seeder.
 *
 * <p>Self-contained (D5): every scenario registers its OWN fresh user; self-scoped: inventory and
 * waste are per-user (the server resolves {@code userId} from the session), so every assertion
 * looks only at THIS user's item / entry, never a global count. The supplier-product cache is
 * global reference data (no per-user ownership per {@code SupplierProductsController}), so the
 * search there is keyed on a per-scenario-random {@code (supplier, productId)} natural key — the
 * created row is unique to this run and found by its own mapping key, never asserting a global
 * table size.
 *
 * <p>Plumbing-not-content: assertions target the stored shape (status, the field we wrote, the
 * lifecycle state, the audit/aggregate counts for THIS row), never derived/AI content.
 */
public class ProvisionsSteps {

  private static final String INVENTORY = "/api/v1/provisions/inventory";
  private static final String WASTE = "/api/v1/provisions/waste";
  private static final String SUPPLIER_PRODUCTS = "/api/v1/provisions/supplier-products";
  private static final String GROCERY_IMPORT = "/api/v1/provisions/grocery-import";
  private static final String PLANNER_BUNDLE = "/api/v1/provisions/planner-bundle";

  // Cross-step keys (domain-namespaced so two domains never collide — see ScenarioContext javadoc).
  private static final String ITEM_ID = "provisions.itemId";
  private static final String ITEM_NAME = "provisions.itemName";
  private static final String ITEM_VERSION = "provisions.itemVersion";
  private static final String WASTE_ITEM_NAME = "provisions.wasteItemName";
  private static final String SUPPLIER = "provisions.supplier";
  private static final String PRODUCT_ID = "provisions.productId";
  private static final String MAPPING_KEY = "provisions.mappingKey";

  private final ScenarioContext context;

  public ProvisionsSteps(ScenarioContext context) {
    this.context = context;
  }

  // ---------------- Inventory: create / read / update / soft-delete ----------------

  @When("they add a fridge inventory item")
  public void theyAddAFridgeInventoryItem() {
    String name = "E2E Chicken Thighs " + shortId();
    context.put(ITEM_NAME, name);
    context.setLastResponse(context.api().post(INVENTORY, fridgeItem(name)));
  }

  @Then("the item is stored and visible in their inventory for this user")
  public void theItemIsStoredAndVisibleInTheirInventoryForThisUser() {
    Response created = context.lastResponse();
    assertThat(created.statusCode())
        .as("inventory create should return 201 Created")
        .isEqualTo(201);
    assertThat(created.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(created.jsonPath().getString("name")).isEqualTo(context.get(ITEM_NAME));
    assertThat(created.jsonPath().getString("itemStatus")).isEqualTo("ACTIVE");
    assertThat(created.jsonPath().getString("source")).isEqualTo("MANUAL_ADD");
    rememberItem(created);

    // Self-scoped: the new item appears in THIS user's active list (the list is per-user; the
    // server resolves userId from the session — never a global count). Match on this item's id.
    Response list = context.api().get(INVENTORY);
    context.setLastResponse(list);
    assertThat(list.statusCode()).as("inventory list should return 200 OK").isEqualTo(200);
    assertThat(list.jsonPath().getList("content.id", String.class)).contains(itemId());
  }

  @When("they read that inventory item by id")
  public void theyReadThatInventoryItemById() {
    context.setLastResponse(context.api().get(INVENTORY + "/" + itemId()));
  }

  @Then("the inventory item read returns the same item for this user")
  public void theInventoryItemReadReturnsTheSameItemForThisUser() {
    Response read = context.lastResponse();
    assertThat(read.statusCode()).as("inventory get by id should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("id")).isEqualTo(itemId());
    assertThat(read.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(read.jsonPath().getString("name")).isEqualTo(context.get(ITEM_NAME));
  }

  @When("they correct that inventory item's quantity")
  public void theyCorrectThatInventoryItemsQuantity() {
    // PROV-12: a full-replacement PUT correcting the quantity (600g -> 400g). expectedVersion
    // carries the @Version the create returned (a mismatch would surface as 409). itemStatus is
    // @NotNull on the update body, so we send ACTIVE explicitly (it stays active).
    Map<String, Object> body = fridgeItem(context.get(ITEM_NAME));
    body.put("quantity", 400);
    body.put("itemStatus", "ACTIVE");
    body.put("expectedVersion", context.<Long>get(ITEM_VERSION));
    context.setLastResponse(
        context.api().request().body(body).when().put(INVENTORY + "/" + itemId()));
  }

  @Then("the corrected quantity is reflected on a read for this user")
  public void theCorrectedQuantityIsReflectedOnAReadForThisUser() {
    Response put = context.lastResponse();
    assertThat(put.statusCode()).as("inventory PUT should return 200 OK").isEqualTo(200);
    assertThat(put.jsonPath().getFloat("quantity")).isEqualTo(400.0f);
    rememberItem(put);

    Response read = context.api().get(INVENTORY + "/" + itemId());
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("inventory get by id should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getFloat("quantity")).isEqualTo(400.0f);
  }

  @When("they soft-delete that inventory item")
  public void theySoftDeleteThatInventoryItem() {
    context.setLastResponse(context.api().request().when().delete(INVENTORY + "/" + itemId()));
  }

  @Then("the item is no longer in their active inventory for this user")
  public void theItemIsNoLongerInTheirActiveInventoryForThisUser() {
    // The controller documents soft-delete as 204 No Content + itemStatus=WASTED; the item drops
    // out of the active-list query. Self-scoped: assert THIS item's id is absent from the active
    // list (never a global count).
    Response delete = context.lastResponse();
    assertThat(delete.statusCode())
        .as("inventory soft-delete should return 204 No Content")
        .isEqualTo(204);

    Response list = context.api().get(INVENTORY);
    context.setLastResponse(list);
    assertThat(list.statusCode()).as("inventory list should return 200 OK").isEqualTo(200);
    assertThat(list.jsonPath().getList("content.id", String.class)).doesNotContain(itemId());
  }

  // ---------------- Inventory: read (empty) + errors ----------------

  @When("they list their inventory")
  public void theyListTheirInventory() {
    context.setLastResponse(context.api().get(INVENTORY));
  }

  @Then("the inventory is empty for this user")
  public void theInventoryIsEmptyForThisUser() {
    // PROV-06 (cold-start): a fresh user's active inventory page is empty (Page.empty). Self-scoped
    // — assert THIS user's content is empty, never a global table count (safe in clean + soak
    // mode).
    Response list = context.lastResponse();
    assertThat(list.statusCode()).as("inventory list should return 200 OK").isEqualTo(200);
    assertThat(list.jsonPath().getList("content")).as("inventory content").isEmpty();
    assertThat(list.jsonPath().getLong("totalElements")).as("inventory totalElements").isZero();
  }

  @When("they read an inventory item by a random non-existent id")
  public void theyReadAnInventoryItemByARandomNonExistentId() {
    context.setLastResponse(context.api().get(INVENTORY + "/" + UUID.randomUUID()));
  }

  @Then("the inventory item read is rejected as not found")
  public void theInventoryItemReadIsRejectedAsNotFound() {
    assertThat(context.lastResponse().statusCode())
        .as("a read of an unknown inventory item id must be 404 Not Found")
        .isEqualTo(404);
  }

  @When("they submit a manual inventory item with no name")
  public void theySubmitAManualInventoryItemWithNoName() {
    // PROV-02: @NotBlank name is the one enumerated required field; a blank name must be rejected.
    Map<String, Object> body = fridgeItem("placeholder");
    body.put("name", "");
    context.setLastResponse(context.api().post(INVENTORY, body));
  }

  @When("they submit a fridge inventory item with status tracking")
  public void theySubmitAFridgeInventoryItemWithStatusTracking() {
    // PROV-02 (structural validation): @ValidStorageLocation requires FRIDGE -> QUANTITY tracking;
    // a FRIDGE item with STATUS tracking is a structurally invalid combination (400).
    Map<String, Object> body = fridgeItem("E2E Bad Tracking " + shortId());
    body.put("trackingMode", "STATUS");
    context.setLastResponse(context.api().post(INVENTORY, body));
  }

  @Then("the inventory item create is rejected as a validation error")
  public void theInventoryItemCreateIsRejectedAsAValidationError() {
    assertThat(context.lastResponse().statusCode())
        .as("an invalid inventory create body must be rejected with 400 Bad Request")
        .isEqualTo(400);
  }

  @When("an anonymous client lists inventory with no session")
  public void anAnonymousClientListsInventoryWithNoSession() {
    // A FRESH client (its own empty cookie jar) is unambiguously anonymous; the deny-by-default
    // chain must 401 before any domain logic.
    context.setLastResponse(new ApiClient().get(INVENTORY));
  }

  // ---------------- Waste: log + history + summary ----------------

  @When("they log a waste entry")
  public void theyLogAWasteEntry() {
    // PROV-19: a standalone waste log (no inventoryItemId link, so no inventory-deduction guard).
    // reason=EXPIRED with a cost estimate; occurredOn must be PastOrPresent.
    String itemName = "E2E Spinach " + shortId();
    context.put(WASTE_ITEM_NAME, itemName);
    Map<String, Object> body = new HashMap<>();
    body.put("itemName", itemName);
    body.put("quantity", 200);
    body.put("unit", "g");
    body.put("reason", "EXPIRED");
    body.put("costEstimate", 1.50);
    body.put("occurredOn", LocalDate.now().toString());
    body.put("notes", "forgotten at the back of the fridge");
    context.setLastResponse(context.api().post(WASTE, body));
  }

  @Then("the waste entry is recorded for this user")
  public void theWasteEntryIsRecordedForThisUser() {
    Response created = context.lastResponse();
    assertThat(created.statusCode()).as("waste log should return 201 Created").isEqualTo(201);
    assertThat(created.jsonPath().getString("id")).isNotBlank();
    assertThat(created.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(created.jsonPath().getString("itemName")).isEqualTo(context.get(WASTE_ITEM_NAME));
    assertThat(created.jsonPath().getString("reason")).isEqualTo("EXPIRED");
  }

  @And("the waste entry appears in their waste history for this user")
  public void theWasteEntryAppearsInTheirWasteHistoryForThisUser() {
    // History defaults to the last 90 days; occurredOn=today is in-window. Self-scoped: assert THIS
    // entry's itemName is present in THIS user's (per-user) history page, never a global count.
    Response history = context.api().get(WASTE);
    context.setLastResponse(history);
    assertThat(history.statusCode()).as("waste history should return 200 OK").isEqualTo(200);
    assertThat(history.jsonPath().getList("content.itemName", String.class))
        .contains(context.<String>get(WASTE_ITEM_NAME));
  }

  @When("they read their waste summary for the last 90 days")
  public void theyReadTheirWasteSummaryForTheLast90Days() {
    LocalDate to = LocalDate.now();
    LocalDate from = to.minusDays(90);
    context.setLastResponse(context.api().get(WASTE + "/summary?from=" + from + "&to=" + to));
  }

  @Then("the waste summary reflects the logged entry for this user")
  public void theWasteSummaryReflectsTheLoggedEntryForThisUser() {
    // PROV-22: the per-user aggregate over the window. Self-scoped: this user logged exactly one
    // EXPIRED entry, so the summary's totalEntries is 1 and EXPIRED counts 1 (the summary is scoped
    // to the calling user — never a global table aggregate).
    Response summary = context.lastResponse();
    assertThat(summary.statusCode()).as("waste summary should return 200 OK").isEqualTo(200);
    assertThat(summary.jsonPath().getLong("totalEntries"))
        .as("this user logged exactly one waste entry")
        .isEqualTo(1L);
    assertThat(summary.jsonPath().getLong("countByReason.EXPIRED"))
        .as("the one entry was reason=EXPIRED")
        .isEqualTo(1L);
  }

  @When("they read their waste summary with an inverted date range")
  public void theyReadTheirWasteSummaryWithAnInvertedDateRange() {
    // PROV (error): the summary rejects from > to with 400 (validateDateRange in WasteController).
    LocalDate to = LocalDate.now().minusDays(30);
    LocalDate from = LocalDate.now();
    context.setLastResponse(context.api().get(WASTE + "/summary?from=" + from + "&to=" + to));
  }

  @Then("the waste summary is rejected as a validation error")
  public void theWasteSummaryIsRejectedAsAValidationError() {
    assertThat(context.lastResponse().statusCode())
        .as("from must be on or before to — expected 400 Bad Request")
        .isEqualTo(400);
  }

  @When("they read their empty waste history")
  public void theyReadTheirEmptyWasteHistory() {
    context.setLastResponse(context.api().get(WASTE));
  }

  @Then("the waste history is empty for this user")
  public void theWasteHistoryIsEmptyForThisUser() {
    Response history = context.lastResponse();
    assertThat(history.statusCode()).as("waste history should return 200 OK").isEqualTo(200);
    assertThat(history.jsonPath().getList("content")).as("waste history content").isEmpty();
    assertThat(history.jsonPath().getLong("totalElements")).as("waste totalElements").isZero();
  }

  // ---------------- Supplier products: upsert + search ----------------

  @When("they cache a supplier product")
  public void theyCacheASupplierProduct() {
    // PROV-33: upsert keyed by (supplier, productId). Use per-scenario-random natural keys so the
    // created row is unique to this run and can be found by its own mapping key on search (the
    // cache
    // is global reference data — there is no per-user ownership, so a per-run unique key is the
    // self-scoping mechanism). lastChecked must be PastOrPresent.
    String productId = "e2e-prod-" + shortId();
    String supplier = "e2e-shop-" + shortId();
    String mappingKey = "e2e-key-" + shortId();
    context.put(PRODUCT_ID, productId);
    context.put(SUPPLIER, supplier);
    context.put(MAPPING_KEY, mappingKey);

    Map<String, Object> body = new HashMap<>();
    body.put("productId", productId);
    body.put("supplier", supplier);
    body.put("name", "E2E Chicken Thighs 1kg");
    body.put("price", 4.50);
    body.put("pricePerUnit", 0.0045);
    body.put("unit", "g");
    body.put("packSizeG", 1000);
    body.put("packSizeUnit", "g");
    body.put("category", "meat");
    body.put("clubcardPrice", 4.00);
    body.put("lastChecked", LocalDate.now().toString());
    body.put("ingredientMappingKey", mappingKey);
    context.setLastResponse(context.api().post(SUPPLIER_PRODUCTS, body));
  }

  @Then("the supplier product is created")
  public void theSupplierProductIsCreated() {
    // Insert on a new (supplier, productId) natural key returns 201 Created (200 on update).
    Response created = context.lastResponse();
    assertThat(created.statusCode())
        .as("a new supplier product should return 201 Created")
        .isEqualTo(201);
    assertThat(created.jsonPath().getString("id")).isNotBlank();
    assertThat(created.jsonPath().getString("productId")).isEqualTo(context.get(PRODUCT_ID));
    assertThat(created.jsonPath().getString("supplier")).isEqualTo(context.get(SUPPLIER));
  }

  @When("they search supplier products by that mapping key")
  public void theySearchSupplierProductsByThatMappingKey() {
    context.setLastResponse(
        context.api().get(SUPPLIER_PRODUCTS + "?mappingKey=" + context.<String>get(MAPPING_KEY)));
  }

  @Then("the cached supplier product is found by its mapping key")
  public void theCachedSupplierProductIsFoundByItsMappingKey() {
    // PROV-29: the search filters by the per-run-unique mapping key, so exactly this scenario's row
    // matches — found by its own key, never asserting the global cache size.
    Response search = context.lastResponse();
    assertThat(search.statusCode())
        .as("supplier-products search should return 200 OK")
        .isEqualTo(200);
    assertThat(search.jsonPath().getList("content.productId", String.class))
        .contains(context.<String>get(PRODUCT_ID));
  }

  // ---------------- Grocery import (PROV-03 leg) ----------------

  @When("they import a confirmed grocery order")
  public void theyImportAConfirmedGroceryOrder() {
    // PROV-03: the grocery module's order -> inventory + supplier-cache write, exercised over the
    // real POST. Idempotent on (userId, supplier, orderRef); a per-run-random orderRef keeps each
    // scenario self-contained. One clean line so the expiry-aware merge-or-create writes one row.
    String orderRef = "e2e-order-" + shortId();
    String supplier = "e2e-shop-" + shortId();
    String mappingKey = "e2e-key-" + shortId();
    context.put(MAPPING_KEY, mappingKey);

    Map<String, Object> line = new HashMap<>();
    line.put("productId", "e2e-prod-" + shortId());
    line.put("name", "E2E Carrots 500g");
    line.put("ingredientMappingKey", mappingKey);
    line.put("quantity", 500);
    line.put("unit", "g");
    line.put("pricePaid", 0.60);
    line.put("category", "vegetable");
    line.put("packSizeG", 500);

    Map<String, Object> body = new HashMap<>();
    body.put("supplier", supplier);
    body.put("orderRef", orderRef);
    body.put("deliveredOn", LocalDate.now().toString());
    body.put("lines", java.util.List.of(line));
    context.setLastResponse(context.api().post(GROCERY_IMPORT, body));
  }

  @Then("the order is applied to inventory and the supplier cache for this user")
  public void theOrderIsAppliedToInventoryAndTheSupplierCacheForThisUser() {
    // The result partitions rows by what happened. A fresh user has no prior matching row, so the
    // line lands as an addedItem and refreshes a supplier product. Self-scoped: assert THIS
    // import's
    // result carries the added inventory row + supplier-product update (never a global count).
    Response result = context.lastResponse();
    assertThat(result.statusCode()).as("grocery import should return 200 OK").isEqualTo(200);
    assertThat(result.jsonPath().getList("addedItems"))
        .as("the new line should create one inventory row")
        .isNotEmpty();
    assertThat(result.jsonPath().getList("updatedSupplierProducts"))
        .as("the import should refresh the supplier cache")
        .isNotEmpty();
    assertThat(result.jsonPath().getString("addedItems[0].userId")).isEqualTo(context.userId());
  }

  // ---------------- Planner bundle (cross-module read, empty-state) ----------------

  @When("they read their planner bundle")
  public void theyReadTheirPlannerBundle() {
    context.setLastResponse(context.api().get(PLANNER_BUNDLE));
  }

  @Then("the planner bundle is empty for this user")
  public void thePlannerBundleIsEmptyForThisUser() {
    // The planner-facing read snapshot. For a fresh user every collection is empty and budget is
    // null (the 01f no-budget divergence). Self-scoped: assert THIS user's bundle is the empty
    // shape
    // (userId echoed, no inventory) — never a global aggregate.
    Response bundle = context.lastResponse();
    assertThat(bundle.statusCode()).as("planner bundle should return 200 OK").isEqualTo(200);
    assertThat(bundle.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(bundle.jsonPath().getList("activeInventory")).as("activeInventory").isEmpty();
    assertThat(bundle.jsonPath().getList("staplesAtLowOrOut")).as("staplesAtLowOrOut").isEmpty();
  }

  // ---------------- helpers ----------------

  /** A valid full create/replacement body for a FRIDGE (QUANTITY-tracked, no freezer) item. */
  private static Map<String, Object> fridgeItem(String name) {
    Map<String, Object> body = new HashMap<>();
    body.put("name", name);
    body.put("category", "meat");
    body.put("storageLocation", "FRIDGE");
    body.put("trackingMode", "QUANTITY");
    body.put("quantity", 600);
    body.put("unit", "g");
    body.put("costPaid", 4.50);
    body.put("status", null);
    body.put("isStaple", false);
    body.put("expiryDate", LocalDate.now().plusDays(3).toString());
    body.put("ingredientMappingKey", "chicken-thighs");
    body.put("notes", "bought at the market");
    body.put("source", "MANUAL_ADD");
    body.put("sourceRef", null);
    body.put("freezerExtension", null);
    return body;
  }

  private void rememberItem(Response response) {
    context.put(ITEM_ID, response.jsonPath().getString("id"));
    context.put(ITEM_VERSION, response.jsonPath().getLong("version"));
  }

  private String itemId() {
    return context.get(ITEM_ID);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
