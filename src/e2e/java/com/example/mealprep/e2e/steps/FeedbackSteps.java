package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Feedback step definitions (feedback.md): submit free text, the cheap-AI classification + routing
 * fan-out, the clarification queue, recent-history reads, and the headline errors — exercised over
 * the black-box HTTP API (decision D2).
 *
 * <p><b>The load-bearing findings that shape this feature</b> (from reading {@code
 * FeedbackController}, {@code FeedbackClassificationListener}, {@code FeedbackRouterImpl}, {@code
 * ConfidenceGate}, the destination dispatchers/bridges, and {@code FeedbackClassificationFlowIT}):
 *
 * <ul>
 *   <li><b>Submit is ASYNC.</b> {@code POST /api/v1/feedback} returns 202 with the entry in {@code
 *       RECEIVED}; an {@code AFTER_COMMIT @Async} listener classifies it. The terminal {@code
 *       submissionStatus} is observed by POLLING {@code GET /api/v1/feedback/{id}} (mirrors the
 *       IT's {@code awaitState}), never asserted synchronously after the POST.
 *   <li><b>Classification crosses the AI.</b> Every submit dispatches {@code
 *       FEEDBACK_CLASSIFICATION} — so each submit primes the e2e AI stub with a realistic {@code
 *       ClassificationResult} first (an unprimed/invalid response makes the entry terminal-{@code
 *       FAILED}, not a 502: the listener catches {@code AiInvalidResponseException} and marks
 *       {@code FAILED}).
 *   <li><b>Confidence gate (locked):</b> {@code >=0.8} AUTO_ROUTED; {@code [0.5,0.8)}
 *       ROUTED_WITH_FLAG; ANY classification {@code <0.5} pauses the whole entry for clarification.
 *       Empty classifications settle the entry to {@code ROUTED} with zero routes.
 *   <li><b>Routing fails gracefully.</b> A destination whose aggregate is missing for a fresh user
 *       records a FAILED routing row, never an HTTP error. The PROVISIONS {@code MARK_DEPLETED}
 *       action is an idempotent no-op SUCCESS when the user owns no matching inventory — so it
 *       routes to {@code APPLIED} for a fresh user with no seeding (the clean submit→APPLIED path).
 * </ul>
 *
 * <p>Self-contained (D5): every scenario registers its OWN fresh user; self-scoped: feedback is
 * per-user (the server resolves {@code userId} from the session), so every assertion looks only at
 * THIS user's entries / clarifications, never a global count.
 */
public class FeedbackSteps {

  private static final String FEEDBACK = "/api/v1/feedback";
  private static final String CLARIFICATIONS = FEEDBACK + "/clarifications";
  private static final String CORRECTIONS = FEEDBACK + "/corrections";

  /** E2E-only PREFERENCE-routed seeder (E2eFeedbackSeedController, @Profile e2e). */
  private static final String SEED_PREFERENCE_FEEDBACK =
      "/test-support/feedback/preference-routed/seed";

  /** Cross-step keys (domain-namespaced — see ScenarioContext javadoc). */
  private static final String FEEDBACK_ID = "feedback.feedbackId";

  private static final String ROUTING_ID = "feedback.routingId";

  /**
   * The recipe-domain key {@code RecipeSteps} stashes the created recipe id under (shared via the
   * one PicoContainer {@link ScenarioContext}) — read by the XJ-02 fan-out to target the RECIPE
   * destination at the real catalogue recipe.
   */
  private static final String RECIPE_ID = "recipe.id";

  /** Polling budget for the async classify→route apply (matches the IT's 30s). */
  private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);

  private static final long POLL_INTERVAL_MS = 250L;

  private final ScenarioContext context;
  private final AiStubSteps aiStub;

  public FeedbackSteps(ScenarioContext context, AiStubSteps aiStub) {
    this.context = context;
    this.aiStub = aiStub;
  }

  // ---------------- AI priming (prime BEFORE the submit that triggers classification) ----------

  @And("the AI will classify the next feedback to provisions at high confidence")
  public void theAiWillClassifyTheNextFeedbackToProvisionsAtHighConfidence() {
    // A single AUTO_ROUTED (>=0.8) PROVISIONS classification with MARK_DEPLETED — the provisions
    // bridge treats "out of something the user doesn't track" as an idempotent no-op SUCCESS, so
    // the
    // route lands APPLIED for a fresh user with no seeded inventory (the clean submit→APPLIED
    // path).
    aiStub.primeAi(
        TaskType.FEEDBACK_CLASSIFICATION,
        "{"
            + "\"classifications\":[{"
            + "\"destination\":\"PROVISIONS\","
            + "\"confidence\":0.92,"
            + "\"extractedFeedback\":\"out of soy sauce\","
            + "\"structuredPayload\":{\"provisionsAction\":\"MARK_DEPLETED\","
            + "\"ingredientMappingKey\":\"soy-sauce\"}"
            + "}],"
            + "\"overallConfidence\":0.92,"
            + "\"classifierNotes\":null}");
  }

  @And("the AI will classify the next feedback as non-actionable")
  public void theAiWillClassifyTheNextFeedbackAsNonActionable() {
    // Empty classifications: the classifier deems the text non-actionable (praise/UX). The entry is
    // still stored and the gate settles it to ROUTED with zero routes (FEED-13).
    aiStub.primeAi(
        TaskType.FEEDBACK_CLASSIFICATION,
        "{\"classifications\":[],\"overallConfidence\":0.10,\"classifierNotes\":\"non-actionable\"}");
  }

  @And("the AI will classify the next feedback at low confidence")
  public void theAiWillClassifyTheNextFeedbackAtLowConfidence() {
    // A single classification below the 0.5 floor — the gate pauses the WHOLE entry for
    // clarification (CLARIFICATION_PENDING + a clarification-query row), with no destination write.
    aiStub.primeAi(
        TaskType.FEEDBACK_CLASSIFICATION,
        "{"
            + "\"classifications\":[{"
            + "\"destination\":\"RECIPE\","
            + "\"confidence\":0.30,"
            + "\"extractedFeedback\":\"ambiguous\","
            + "\"structuredPayload\":{\"note\":\"ambiguous\"}"
            + "}],"
            + "\"overallConfidence\":0.30,"
            + "\"classifierNotes\":null}");
  }

  @And("the AI will classify the next feedback to all four destinations")
  public void theAiWillClassifyTheNextFeedbackToAllFourDestinations() {
    // XJ-02 fan-out: ONE feedback the classifier splits across all four destinations
    // (RECIPE/PREFERENCE/NUTRITION/PROVISIONS — the @Size(max = 4) universe). EVERY confidence is
    // >= 0.8 so each route AUTO_ROUTES (a single classification < 0.5 would pause the WHOLE entry
    // for clarification). The destination outcomes the downstream bridges produce:
    //   PROVISIONS — MARK_DEPLETED of an untracked staple → idempotent no-op → APPLIED.
    //   NUTRITION  — protein_target_g INCREASE/SMALL: a valid FeedbackTargetResolver target on the
    //                targets the scenario set first (+5% relative) → APPLIED. (A target not on the
    //                allow-list would FAIL the route and tip the entry to PARTIALLY_FAILED.)
    //   PREFERENCE — an EMPTY delta batch (trigger BATCH) is a no-op success against the seeded
    //                taste profile → APPLIED. (A FRESH user with no profile would 404 → FAILED;
    //                hence the "initialised taste profile" precondition.)
    //   RECIPE     — recipeId is the real catalogue recipe created earlier; the recipe bridge
    //                enqueues a synchronous adaptation FEEDBACK job (ApprovalPolicy
    // PENDING_CHANGE),
    //                which creates a PendingChange → requiresApproval → AWAITING_USER_APPROVAL (a
    //                non-failed, non-APPLIED propose/approve outcome). The adaptation Stage-C AI is
    //                NOT primed here: the e2e stub ships a built-in NO_CHANGE RECIPE_ADAPTATION
    //                default that clears both validation gates, and a PENDING_CHANGE job stores the
    //                pending change regardless of the NO_CHANGE classification.
    // All four routes are non-FAILED, so the entry reconciles to ROUTED (not PARTIALLY_FAILED).
    String recipeId = context.get(RECIPE_ID);
    assertThat(recipeId)
        .as("a recipe must be created (recipe.id stashed) before priming the recipe destination")
        .isNotBlank();
    aiStub.primeAi(
        TaskType.FEEDBACK_CLASSIFICATION,
        "{"
            + "\"classifications\":["
            + "{\"destination\":\"PROVISIONS\",\"confidence\":0.92,"
            + "\"extractedFeedback\":\"out of soy sauce\","
            + "\"structuredPayload\":{\"provisionsAction\":\"MARK_DEPLETED\","
            + "\"ingredientMappingKey\":\"soy-sauce\"}},"
            + "{\"destination\":\"NUTRITION\",\"confidence\":0.90,"
            + "\"extractedFeedback\":\"more protein\","
            + "\"structuredPayload\":{\"target\":\"protein_target_g\",\"direction\":\"INCREASE\","
            + "\"magnitude\":\"SMALL\"}},"
            + "{\"destination\":\"PREFERENCE\",\"confidence\":0.88,"
            + "\"extractedFeedback\":\"love prawns\","
            + "\"structuredPayload\":{\"deltas\":[],\"trigger\":\"BATCH\"}},"
            + "{\"destination\":\"RECIPE\",\"confidence\":0.91,"
            + "\"extractedFeedback\":\"that recipe needed salt\","
            + "\"structuredPayload\":{\"recipeId\":\""
            + recipeId
            + "\",\"ratingDelta\":{\"taste\":-0.2}}}"
            + "],"
            + "\"overallConfidence\":0.90,"
            + "\"classifierNotes\":null}");
  }

  @And("the AI will reclassify the answered clarification to provisions at high confidence")
  public void theAiWillReclassifyTheAnsweredClarificationToProvisionsAtHighConfidence() {
    // Re-prime (OVERWRITE) the single FEEDBACK_CLASSIFICATION canned response: the clarification
    // answer re-fires the async classifier on the SAME entry, which calls the AI a SECOND time. The
    // first (low-confidence RECIPE) response is replaced by this AUTO_ROUTED (>=0.8) PROVISIONS
    // MARK_DEPLETED result so the second pass routes to the clean fresh-user APPLIED path and the
    // entry settles ROUTED. The e2e stub keys one canned response per TaskType, so this overwrite
    // is the deterministic seam (no per-call queue needed). Primed BEFORE the answer so the
    // response
    // is already in place when the async re-classification picks the event up.
    aiStub.primeAi(
        TaskType.FEEDBACK_CLASSIFICATION,
        "{"
            + "\"classifications\":[{"
            + "\"destination\":\"PROVISIONS\","
            + "\"confidence\":0.92,"
            + "\"extractedFeedback\":\"out of soy sauce\","
            + "\"structuredPayload\":{\"provisionsAction\":\"MARK_DEPLETED\","
            + "\"ingredientMappingKey\":\"soy-sauce\"}"
            + "}],"
            + "\"overallConfidence\":0.92,"
            + "\"classifierNotes\":null}");
  }

  // ---------------- e2e setup (seeder) ----------------

  @Given("the user has a seeded preference-routed feedback entry")
  public void theUserHasASeededPreferenceRoutedFeedbackEntry() {
    // Persists the SAME real FeedbackEntry + APPLIED PREFERENCE RoutingLogEntry the
    // PreferenceDeltaPipelineIT seeds, so a routed-entry read is asserted without async timing.
    Response response = context.api().request().when().post(SEED_PREFERENCE_FEEDBACK);
    context.setLastResponse(response);
    assertThat(response.statusCode())
        .as("seeding a preference-routed feedback entry should return a 2xx (e2e seeder)")
        .isBetween(200, 299);
    context.put(FEEDBACK_ID, response.jsonPath().getString("feedbackId"));
    context.put(ROUTING_ID, response.jsonPath().getString("routingId"));
  }

  // ---------------- submit ----------------

  @When("they submit feedback {string}")
  public void theySubmitFeedback(String text) {
    context.setLastResponse(context.api().post(FEEDBACK, generalFeedbackBody(text)));
  }

  @When("they submit feedback with blank text")
  public void theySubmitFeedbackWithBlankText() {
    // @NotBlank text — a blank string is a synchronous 400 before any classifier call.
    context.setLastResponse(context.api().post(FEEDBACK, generalFeedbackBody("   ")));
  }

  @When("they submit feedback text that exceeds the length limit")
  public void theySubmitFeedbackTextThatExceedsTheLengthLimit() {
    // @Size(max = 4000) — 4001 chars is a synchronous 400.
    context.setLastResponse(context.api().post(FEEDBACK, generalFeedbackBody("x".repeat(4001))));
  }

  @When("an anonymous client submits feedback with no session")
  public void anAnonymousClientSubmitsFeedbackWithNoSession() {
    // A FRESH client (its own empty cookie jar) is unambiguously anonymous; the deny-by-default
    // chain must 401 before any feedback logic. A syntactically valid body isolates the 401 to the
    // missing session, not body validation.
    context.setLastResponse(new ApiClient().post(FEEDBACK, generalFeedbackBody("anything")));
  }

  @Then("the feedback submission is accepted for processing")
  public void theFeedbackSubmissionIsAcceptedForProcessing() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("submit should return 202 Accepted (classification runs async)")
        .isEqualTo(202);
    // 01b: the synchronous receipt is always RECEIVED with no routes yet.
    assertThat(response.jsonPath().getString("submissionStatus")).isEqualTo("RECEIVED");
    String feedbackId = response.jsonPath().getString("feedbackId");
    assertThat(feedbackId).as("submit must mint a feedback id").isNotBlank();
    context.put(FEEDBACK_ID, feedbackId);
  }

  @Then("the feedback submission is rejected as a validation error")
  public void theFeedbackSubmissionIsRejectedAsAValidationError() {
    assertThat(context.lastResponse().statusCode())
        .as("an invalid feedback body must be rejected with 400 Bad Request")
        .isEqualTo(400);
  }

  // ---------------- async terminal-state assertions (poll GET /{id}) ----------------

  @Then("the feedback entry eventually reaches a routed state for this user")
  public void theFeedbackEntryEventuallyReachesARoutedStateForThisUser() {
    // The async listener fans out and reconciles to ROUTED (all routes non-failed). Poll the entry
    // until its submissionStatus settles there (mirrors FeedbackClassificationFlowIT.awaitState).
    awaitStatus("ROUTED");
  }

  @Then("the feedback entry has a routing decision to provisions for this user")
  public void theFeedbackEntryHasARoutingDecisionToProvisionsForThisUser() {
    Response read = readById(feedbackId());
    context.setLastResponse(read);
    assertThat(read.jsonPath().getList("routes")).as("routes for this entry").isNotEmpty();
    assertThat(read.jsonPath().getList("routes.destination", String.class)).contains("PROVISIONS");
    // The provisions MARK_DEPLETED no-op is an APPLIED route for a fresh user.
    assertThat(read.jsonPath().getString("routes[0].status")).isEqualTo("APPLIED");
  }

  @Then("the feedback entry has four routed destinations for this user")
  public void theFeedbackEntryHasFourRoutedDestinationsForThisUser() {
    // The entry has already settled ROUTED (the preceding "eventually reaches a routed state"
    // step).
    // Read it and assert the genuine four-way fan-out: every destination is present, the three
    // update destinations APPLIED, and the propose/approve RECIPE route is AWAITING_USER_APPROVAL.
    Response read = readById(feedbackId());
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("feedback get by id should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("submissionStatus"))
        .as("a clean four-way fan-out reconciles to ROUTED (all routes non-failed)")
        .isEqualTo("ROUTED");
    assertThat(read.jsonPath().getList("routes.destination", String.class))
        .as("all four destinations are routed")
        .contains("PROVISIONS", "NUTRITION", "PREFERENCE", "RECIPE");
    // Per-destination status: the three update destinations applied; RECIPE is a proposed (pending
    // approval) adaptation, never an applied one (RoutingStatus.AWAITING_USER_APPROVAL).
    assertThat(routeStatus(read, "PROVISIONS")).as("PROVISIONS route status").isEqualTo("APPLIED");
    assertThat(routeStatus(read, "NUTRITION")).as("NUTRITION route status").isEqualTo("APPLIED");
    assertThat(routeStatus(read, "PREFERENCE")).as("PREFERENCE route status").isEqualTo("APPLIED");
    assertThat(routeStatus(read, "RECIPE"))
        .as("RECIPE route is a proposed adaptation awaiting user approval")
        .isEqualTo("AWAITING_USER_APPROVAL");
  }

  @Then("the feedback entry has no routing decisions for this user")
  public void theFeedbackEntryHasNoRoutingDecisionsForThisUser() {
    Response read = readById(feedbackId());
    context.setLastResponse(read);
    // FEED-13: a non-actionable entry is stored with zero routes.
    assertThat(read.jsonPath().getList("routes")).as("routes for a non-actionable entry").isEmpty();
  }

  @Then("the feedback entry eventually awaits clarification for this user")
  public void theFeedbackEntryEventuallyAwaitsClarificationForThisUser() {
    awaitStatus("CLARIFICATION_PENDING");
    Response read = readById(feedbackId());
    context.setLastResponse(read);
    // No destination write happened — the clarification is pending and surfaced on the entry.
    assertThat(read.jsonPath().getString("pendingClarificationQueryId"))
        .as("a clarifying entry carries the pending clarification-query id")
        .isNotBlank();
    assertThat(read.jsonPath().getList("routes"))
        .as("no routes while awaiting clarification")
        .isEmpty();
  }

  @And("a clarification query is listed for this user")
  public void aClarificationQueryIsListedForThisUser() {
    Response list = context.api().get(CLARIFICATIONS);
    context.setLastResponse(list);
    assertThat(list.statusCode()).as("clarifications list should return 200 OK").isEqualTo(200);
    // Self-scoped: this user's clarification queue carries exactly the query opened for this entry.
    assertThat(list.jsonPath().getList("content.feedbackEntryId", String.class))
        .contains(feedbackId());
  }

  // ---------------- reads ----------------

  @When("they read that feedback entry by id")
  public void theyReadThatFeedbackEntryById() {
    context.setLastResponse(readById(feedbackId()));
  }

  @Then("the feedback entry read returns an applied preference route for this user")
  public void theFeedbackEntryReadReturnsAnAppliedPreferenceRouteForThisUser() {
    Response read = context.lastResponse();
    assertThat(read.statusCode()).as("feedback get by id should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("id")).isEqualTo(feedbackId());
    assertThat(read.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(read.jsonPath().getString("submissionStatus")).isEqualTo("ROUTED");
    assertThat(read.jsonPath().getList("routes.destination", String.class)).contains("PREFERENCE");
    assertThat(read.jsonPath().getString("routes[0].status")).isEqualTo("APPLIED");
  }

  @And("the seeded feedback entry appears in their recent feedback for this user")
  public void theSeededFeedbackEntryAppearsInTheirRecentFeedbackForThisUser() {
    Response list = context.api().get(FEEDBACK);
    context.setLastResponse(list);
    assertThat(list.statusCode()).as("recent feedback list should return 200 OK").isEqualTo(200);
    // Self-scoped: assert THIS entry's id is present in this user's (per-user) list, never a count.
    assertThat(list.jsonPath().getList("content.id", String.class)).contains(feedbackId());
  }

  @When("they list their recent feedback")
  public void theyListTheirRecentFeedback() {
    context.setLastResponse(context.api().get(FEEDBACK));
  }

  @Then("the recent feedback list is empty for this user")
  public void theRecentFeedbackListIsEmptyForThisUser() {
    assertEmptyPage("recent feedback");
  }

  @When("they read a feedback entry by a random non-existent id")
  public void theyReadAFeedbackEntryByARandomNonExistentId() {
    context.setLastResponse(context.api().get(FEEDBACK + "/" + UUID.randomUUID()));
  }

  @Then("the feedback read is rejected as not found")
  public void theFeedbackReadIsRejectedAsNotFound() {
    assertThat(context.lastResponse().statusCode())
        .as("a read of an unknown feedback id must be 404 Not Found")
        .isEqualTo(404);
  }

  @When("they list their feedback corrections")
  public void theyListTheirFeedbackCorrections() {
    context.setLastResponse(context.api().get(CORRECTIONS));
  }

  @Then("the feedback corrections list is empty for this user")
  public void theFeedbackCorrectionsListIsEmptyForThisUser() {
    assertEmptyPage("feedback corrections");
  }

  @When("they list their clarification queue")
  public void theyListTheirClarificationQueue() {
    context.setLastResponse(context.api().get(CLARIFICATIONS));
  }

  @Then("the clarification queue is empty for this user")
  public void theClarificationQueueIsEmptyForThisUser() {
    assertEmptyPage("clarification queue");
  }

  // ---------------- correction + clarification-answer glue ----------------

  @When("they correct that route to nutrition")
  public void theyCorrectThatRouteToNutrition() {
    // The seeded route is an APPLIED PREFERENCE route; per ticket 01f any route that is not already
    // CORRECTED_AWAY/REPLAYED is correctable to a different non-RECIPE destination. Correcting to
    // NUTRITION returns 200 and records the ground-truth row regardless of the synthetic replay's
    // own (fresh-user) outcome — the replay fires in its own REQUIRES_NEW tx.
    Map<String, Object> body =
        Map.of("newDestination", "NUTRITION", "userCorrectionNote", "e2e correction");
    context.setLastResponse(
        context
            .api()
            .post(FEEDBACK + "/" + feedbackId() + "/routes/" + routingId() + "/correct", body));
  }

  @When("they correct the recipe route to nutrition")
  public void theyCorrectTheRecipeRouteToNutrition() {
    // XJ-02 reverter leg. Locate the RECIPE route id from the entry read (NOT the seeded PREFERENCE
    // routingId the sibling correct-step uses) and correct it AWAY from RECIPE to NUTRITION. The
    // original RECIPE route is AWAITING_USER_APPROVAL (a pending adaptation); correcting it away
    // fires the REAL RecipeFeedbackReverterImpl, which resolves the adaptation jobId off the
    // route's
    // destinationResult and CANCELS the pending change via rejectPendingChange. NUTRITION is a
    // valid
    // correction target (not RECIPE — correcting TO RECIPE would need a recipeId; not the
    // original).
    // The synthetic NUTRITION replay reuses the RECIPE payload (recipeId/ratingDelta), which the
    // nutrition bridge cannot parse → the replay route's own outcome may be FAILED, but the
    // correction GROUND-TRUTH row is recorded regardless (the assertion checks the corrections
    // audit,
    // not the replay outcome). Stash the original RECIPE routingId so the shared assertion can
    // match
    // it as the original route.
    Response read = readById(feedbackId());
    context.setLastResponse(read);
    String recipeRoutingId = routeId(read, "RECIPE");
    assertThat(recipeRoutingId)
        .as("the RECIPE route must be present to locate its routing id for correction")
        .isNotBlank();
    context.put(ROUTING_ID, recipeRoutingId);
    Map<String, Object> body =
        Map.of("newDestination", "NUTRITION", "userCorrectionNote", "e2e recipe-route correction");
    context.setLastResponse(
        context
            .api()
            .post(FEEDBACK + "/" + feedbackId() + "/routes/" + recipeRoutingId + "/correct", body));
  }

  @Then("the correction is recorded alongside the original route for this user")
  public void theCorrectionIsRecordedAlongsideTheOriginalRouteForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("correct should return 200 OK").isEqualTo(200);
    Response corrections = context.api().get(CORRECTIONS);
    context.setLastResponse(corrections);
    assertThat(corrections.statusCode()).as("corrections list should return 200 OK").isEqualTo(200);
    // Self-scoped: this user's corrections audit carries exactly the correction for THIS entry,
    // referencing the original (now corrected-away) routing id and the new destination — i.e. the
    // correction is recorded alongside the original route, not just "some" correction.
    assertThat(corrections.jsonPath().getList("content.feedbackEntryId", String.class))
        .as("the correction references this feedback entry")
        .contains(feedbackId());
    assertThat(corrections.jsonPath().getList("content.originalRoutingId", String.class))
        .as("the correction is recorded alongside the original route")
        .contains(routingId());
    assertThat(corrections.jsonPath().getList("content.correctedDestination", String.class))
        .as("the correction targets the new destination")
        .contains("NUTRITION");
  }

  @When("they answer that clarification choosing provisions")
  public void theyAnswerThatClarificationChoosingProvisions() {
    // Resolve the pending clarification-query id for this entry, then answer choosing PROVISIONS.
    // selectedDestination is the user's hint to the re-classifier (prompt context only) — the
    // actual
    // re-route is driven by the SECOND primed AI response (PROVISIONS, high confidence), so picking
    // provisions here keeps the scenario semantically coherent. The answer flips the entry back to
    // RECEIVED and re-publishes FeedbackSubmittedEvent (same traceId) → async re-classification.
    Response queue = context.api().get(CLARIFICATIONS);
    String queryId = queue.jsonPath().getString("content[0].id");
    Map<String, Object> body =
        Map.of(
            "selectedDestination", "PROVISIONS", "userClarificationText", "I'm out of soy sauce");
    context.setLastResponse(context.api().post(CLARIFICATIONS + "/" + queryId + "/answer", body));
    assertThat(context.lastResponse().statusCode())
        .as("answering a pending clarification should return 200 OK")
        .isEqualTo(200);
  }

  // ---------------- helpers ----------------

  private static Map<String, Object> generalFeedbackBody(String text) {
    // A GENERAL-screen context carries no recipe/plan ids, so @ValidUiContext's
    // screen-specific requirements do not apply — the minimal valid submission shape.
    Map<String, Object> ctx = new java.util.HashMap<>();
    ctx.put("screen", "GENERAL");
    ctx.put("recipeId", null);
    ctx.put("recipeVersion", null);
    ctx.put("mealSlotId", null);
    ctx.put("planId", null);
    ctx.put("referenceDate", null);
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("text", text);
    body.put("context", ctx);
    return body;
  }

  private Response readById(String id) {
    return context.api().get(FEEDBACK + "/" + id);
  }

  /**
   * The {@code status} of the route whose {@code destination} equals {@code destination} on a
   * {@code GET /feedback/{id}} response, or {@code null} if no such route exists. Uses a
   * REST-assured GPath find so it is robust to route ordering (the fan-out persists routes in
   * classification order, which is not the assertion's concern).
   */
  private static String routeStatus(Response read, String destination) {
    return read.jsonPath()
        .getString("routes.find { it.destination == '" + destination + "' }.status");
  }

  /** As {@link #routeStatus} but returns the route-log id (used to address the correction). */
  private static String routeId(Response read, String destination) {
    return read.jsonPath().getString("routes.find { it.destination == '" + destination + "' }.id");
  }

  private String feedbackId() {
    return context.get(FEEDBACK_ID);
  }

  private String routingId() {
    return context.get(ROUTING_ID);
  }

  private void assertEmptyPage(String what) {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as(what + " GET should return 200 OK").isEqualTo(200);
    // Self-scoped: a fresh user's page is empty (Page.empty) — assert THIS user's content is empty,
    // never a global table count (safe in both clean and soak mode).
    assertThat(response.jsonPath().getList("content")).as(what + " should be empty").isEmpty();
    assertThat(response.jsonPath().getLong("totalElements")).as(what + " totalElements").isZero();
  }

  /**
   * Poll {@code GET /feedback/{id}} until {@code submissionStatus} reaches {@code expected} or the
   * timeout elapses — the async classify→route apply must not be asserted synchronously after the
   * 202 (mirrors {@code FeedbackClassificationFlowIT.awaitState}).
   */
  private void awaitStatus(String expected) {
    String id = feedbackId();
    awaitCondition(
        () -> {
          Response read = readById(id);
          return read.statusCode() == 200
              && expected.equals(read.jsonPath().getString("submissionStatus"));
        },
        ASYNC_TIMEOUT);
    Response after = readById(id);
    context.setLastResponse(after);
    assertThat(after.jsonPath().getString("submissionStatus")).isEqualTo(expected);
  }

  private static void awaitCondition(BooleanSupplier check, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (check.getAsBoolean()) {
        return;
      }
      try {
        TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while awaiting feedback state", ie);
      }
    }
    if (!check.getAsBoolean()) {
      throw new AssertionError("Timed out waiting for feedback state after " + timeout);
    }
  }
}
