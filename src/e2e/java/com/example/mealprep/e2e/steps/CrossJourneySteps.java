package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * Bridging step definitions for the cross-domain journeys (XJ-01..06, see
 * e2e/pathways/cross-journeys.md). The per-domain step classes ({@link RecipeSteps}, {@link
 * NutritionSteps}, {@link FeedbackSteps}, {@link HouseholdSteps}, {@link PlannerSteps}, {@link
 * ProvisionsSteps}, {@link GrocerySteps}, …) already cover almost every leg, and PicoContainer
 * shares ONE {@link ScenarioContext} across every glue class in a scenario, so most journeys flow
 * by chaining those steps verbatim in the {@code .feature} (their session/aggregate state is shared
 * automatically).
 *
 * <p>This class only adds the handful of NEW, distinctly-phrased steps that bridge a context-key
 * mismatch between two domains — i.e. where a later step needs an id an earlier step stored under a
 * DIFFERENT namespaced key. Each bridge reads the producing domain's key and calls the consuming
 * endpoint via {@code context.api()}. Phrasing is deliberately distinct from every existing step so
 * the suite stays unambiguous (no two glue methods match the same Gherkin text).
 *
 * <p>The two seams that need bridging:
 *
 * <ul>
 *   <li><b>Household → Planner (XJ-04).</b> {@link HouseholdSteps} stashes the created household
 *       under {@code household.householdId} (so the roster / member / slot-setting assertions
 *       work), but {@link PlannerSteps#theyGenerateAPlanForAWeek()} reads {@code
 *       planner.householdId}. A cross-journey that builds a multi-member household via the
 *       Household steps and then generates a plan for THAT household needs a generate keyed off the
 *       Household-domain id (and must prime the same two AI tasks the planner dispatches
 *       synchronously).
 *   <li><b>Grocery/Provisions → Planner read (XJ-05).</b> {@link ProvisionsSteps} only has an
 *       EMPTY-state planner-bundle assertion; after a grocery order flows into inventory the bundle
 *       is NON-empty, which is exactly the Grocery→Provisions→Planner relay the journey asserts —
 *       so a distinct "bundle reflects the fulfilled order" assertion is required.
 * </ul>
 *
 * <p>Self-contained + self-scoped (D5): every bridge asserts only on THIS scenario's household /
 * user state (the server resolves the user from the session and the household id from the prior
 * step), never a global count.
 */
public class CrossJourneySteps {

  private static final String PLANS = "/api/v1/plans";
  private static final String PLANNER_BUNDLE = "/api/v1/provisions/planner-bundle";

  /**
   * Producing-domain key written by {@link HouseholdSteps} on create (read here, never written).
   */
  private static final String HOUSEHOLD_ID = "household.householdId";

  /** Cross-journey-namespaced keys this bridge owns. */
  private static final String XJ_PLAN_ID = "crossjourney.planId";

  private static final String XJ_WEEK_START = "crossjourney.weekStart";

  private final ScenarioContext context;
  private final AiStubSteps aiStub;

  public CrossJourneySteps(ScenarioContext context, AiStubSteps aiStub) {
    this.context = context;
    this.aiStub = aiStub;
  }

  // ---------------- Household → Planner bridge (XJ-04) ----------------

  @When("they generate a shared plan for that household")
  public void theyGenerateASharedPlanForThatHousehold() {
    // The household was created via the Household steps (stashed under household.householdId) so
    // its
    // roster / shared-slot settings could be asserted; the planner's own generate step reads a
    // DIFFERENT key (planner.householdId), so bridge by generating for the Household-domain id.
    //
    // Generate is SYNCHRONOUS and dispatches the AI inside the request (Stage C + Phase 2), so
    // prime
    // the same minimal-safe canned responses PlannerSteps uses — without them the unprimed stub
    // throws and the controller returns HTTP 502. The deterministic hard-constraint union over the
    // two members is computed at generate time (HH-19); with no per-member preference models seeded
    // the soft merge is a no-op and the plan is produced for the multi-eater household.
    aiStub.primeAi(
        TaskType.PLANNER_STAGE_C, "{\"chosenIndex\":0,\"reasoning\":\"e2e deterministic pick\"}");
    aiStub.primeAi(
        TaskType.PLANNER_PHASE2_AUGMENTATION, "{\"augmentations\":[],\"refineDirectives\":[]}");

    LocalDate weekStart = nextMonday();
    context.put(XJ_WEEK_START, weekStart.toString());
    Map<String, Object> body =
        Map.of(
            "householdId", householdId(),
            "weekStartDate", weekStart.toString(),
            "forceRegenerateIfActive", false);
    context.setLastResponse(context.api().post(PLANS + "/generate", body));
  }

  @Then("a shared generated plan is created for that household")
  public void aSharedGeneratedPlanIsCreatedForThatHousehold() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("generate should return 201 Created").isEqualTo(201);
    assertThat(response.jsonPath().getString("status")).isEqualTo("GENERATED");
    // Self-scoped: the plan belongs to THIS scenario's (multi-member) household.
    assertThat(response.jsonPath().getString("householdId")).isEqualTo(householdId());
    assertThat(response.jsonPath().getInt("generation")).isEqualTo(1);
    context.put(XJ_PLAN_ID, response.jsonPath().getString("id"));
  }

  // ---------------- Grocery/Provisions → Planner read bridge (XJ-05) ----------------

  @Then("their planner bundle reflects the fulfilled grocery inventory for this user")
  public void theirPlannerBundleReflectsTheFulfilledGroceryInventoryForThisUser() {
    // The Grocery→Provisions relay: a fulfilled order was auto-added to Provisions inventory, so
    // the
    // planner-facing read snapshot (the bundle the Planner reads to derive demand) now carries that
    // inventory — the cross-module hand-off this journey asserts. Distinct from ProvisionsSteps'
    // EMPTY-state assertion (which asserts activeInventory IS empty for a fresh user).
    Response bundle = context.api().get(PLANNER_BUNDLE);
    context.setLastResponse(bundle);
    assertThat(bundle.statusCode()).as("planner bundle should return 200 OK").isEqualTo(200);
    assertThat(bundle.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(bundle.jsonPath().getList("activeInventory"))
        .as("the fulfilled grocery order should flow into the planner-facing inventory read")
        .isNotEmpty();
  }

  // ---------------- helpers ----------------

  private String householdId() {
    String id = context.get(HOUSEHOLD_ID);
    assertThat(id)
        .as("a household must have been created (household.householdId) before generating a plan")
        .isNotNull();
    return id;
  }

  /** A future Monday so each scenario's (household, week) is clean — mirrors PlannerSteps. */
  private static LocalDate nextMonday() {
    LocalDate d = LocalDate.now().plusDays(7);
    while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
      d = d.plusDays(1);
    }
    return d;
  }
}
