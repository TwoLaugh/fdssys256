package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Preference step definitions (preference.md): the three-tier read/write surface — Tier-1 hard
 * constraints, the Tier-2 taste profile (+ archive), Tier-3 lifestyle config — exercised over the
 * black-box HTTP API (decision D2).
 *
 * <p><b>The load-bearing finding that shapes this feature.</b> A fresh, just-registered user has NO
 * preference aggregates, and there is NO HTTP path to create one. {@code
 * initialiseHardConstraints} / {@code TasteProfileUpdateService.initialise} / {@code
 * LifestyleConfigUpdateService.initialise} are in-process service calls only (called from the
 * in-process directive-apply path and ITs); registration publishes {@code UserRegisteredEvent} but
 * no preference listener consumes it to seed, and the only e2e test-support endpoint is the AI stub
 * — there is no seeder. So for a fresh user: every GET/PUT on an aggregate, {@code refresh-now}, and
 * {@code rollback} return 404 (mirrors the Wave-1 nutrition "PUT 404s when no row exists" lesson),
 * while the version / audit-log / archive LIST endpoints return an empty {@code Page} (200). The
 * green scenarios assert exactly those behaviours; the seed-then-mutate happy paths are {@code
 * @pending} in the feature with that reason, but their full glue is written here so they are ready
 * the moment an HTTP seed path lands.
 *
 * <p>Self-scoped (D5): every read/assert is for THIS user (the server resolves {@code userId} from
 * the session — never from a param), and the empty-collection assertions look only at this user's
 * (always-empty-for-a-fresh-user) pages, never a global count.
 *
 * <p>AI-touching flows use the deterministic AI double: the canned {@code PREFERENCE_DELTA_UPDATE}
 * is seeded via the {@code @Given the AI will return this ... response:} step (in {@link
 * AiStubSteps}), and {@code refresh-now} is ASYNC — the version bump is observed by POLLING {@code
 * GET /taste-profile} until {@code documentVersion} increments, with a timeout (mirrors {@code
 * PreferenceDeltaPipelineIT.awaitCondition}).
 */
public class PreferenceSteps {

  private static final String HARD_CONSTRAINTS = "/api/v1/preferences/hard-constraints";
  private static final String TASTE_PROFILE = "/api/v1/preferences/taste-profile";
  private static final String LIFESTYLE_CONFIG = "/api/v1/preferences/lifestyle-config";
  private static final String ARCHIVE = "/api/v1/preferences/archive";

  /** The allergen this scenario's hard-constraints write adds (self-scoped assertion key). */
  private static final String ALLERGEN = "peanuts";

  /**
   * The document version observed before an async refresh, stashed for the poll-after assertion.
   */
  private static final String TP_VERSION_BEFORE = "preference.tasteProfileVersionBefore";

  /** Polling budget for the async {@code refresh-now} delta apply (matches the IT's 30s). */
  private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(30);

  private static final long POLL_INTERVAL_MS = 250L;

  private final ScenarioContext context;

  public PreferenceSteps(ScenarioContext context) {
    this.context = context;
  }

  // ---------------- Tier-1 hard constraints ----------------

  @When("they read their hard constraints")
  public void theyReadTheirHardConstraints() {
    context.setLastResponse(context.api().get(HARD_CONSTRAINTS));
  }

  @Then("the hard-constraints read is rejected as not found")
  public void theHardConstraintsReadIsRejectedAsNotFound() {
    assertNotFound("a fresh user has no hard-constraints aggregate (none seeded over HTTP)");
  }

  @When("they set an allergy on their hard constraints")
  public void theySetAnAllergyOnTheirHardConstraints() {
    // A full-replacement PUT with a single allergy; expectedVersion 0 is what the first write would
    // use if the aggregate existed. For a fresh user the aggregate does NOT exist, so this 404s
    // (the load-bearing finding) — the happy-path assertion below only runs once a seed path
    // exists.
    context.setLastResponse(putHardConstraints(hardConstraintsBody(List.of(ALLERGEN), 0L)));
  }

  @Then("the hard-constraints update is rejected as not found")
  public void theHardConstraintsUpdateIsRejectedAsNotFound() {
    assertNotFound(
        "a hard-constraints PUT 404s when no aggregate exists (cannot create over HTTP)");
  }

  @Then("the allergy is stored and reflected on a read for this user")
  public void theAllergyIsStoredAndReflectedOnAReadForThisUser() {
    Response put = context.lastResponse();
    assertThat(put.statusCode()).as("hard-constraints PUT should return 200 OK").isEqualTo(200);
    assertThat(put.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(put.jsonPath().getList("allergies", String.class)).contains(ALLERGEN);

    Response read = context.api().get(HARD_CONSTRAINTS);
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("hard-constraints GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getList("allergies", String.class)).contains(ALLERGEN);
  }

  @And("the hard-constraints audit log records the allergy change for this user")
  public void theHardConstraintsAuditLogRecordsTheAllergyChangeForThisUser() {
    Response audit = context.api().get(HARD_CONSTRAINTS + "/audit-log");
    context.setLastResponse(audit);
    assertThat(audit.statusCode()).as("audit-log GET should return 200 OK").isEqualTo(200);
    // Self-scoped: the allergies field change appears in THIS user's audit log (never a global
    // count).
    assertThat(audit.jsonPath().getList("content")).isNotEmpty();
    assertThat(audit.jsonPath().getList("content.field", String.class)).contains("allergies");
  }

  @When("they set a structured dietary identity on their hard constraints")
  public void theySetAStructuredDietaryIdentityOnTheirHardConstraints() {
    Map<String, Object> body = hardConstraintsBody(List.of(), 0L);
    body.put(
        "dietaryIdentity",
        new java.util.HashMap<>(
            Map.of(
                "base",
                "vegetarian",
                "labelForDisplay",
                "pescatarian",
                "exceptions",
                List.of(
                    new java.util.HashMap<>(
                        Map.of("allows", "fish", "frequency", "2-3x/week", "context", "any"))))));
    context.setLastResponse(putHardConstraints(body));
  }

  @Then("the dietary identity is stored and reflected on a read for this user")
  public void theDietaryIdentityIsStoredAndReflectedOnAReadForThisUser() {
    Response put = context.lastResponse();
    assertThat(put.statusCode()).as("hard-constraints PUT should return 200 OK").isEqualTo(200);

    Response read = context.api().get(HARD_CONSTRAINTS);
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("hard-constraints GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("dietaryIdentity.base")).isEqualTo("vegetarian");
    assertThat(read.jsonPath().getString("dietaryIdentity.labelForDisplay"))
        .isEqualTo("pescatarian");
    assertThat(read.jsonPath().getList("dietaryIdentity.exceptions")).isNotEmpty();
  }

  @When("they read their hard-constraints audit log")
  public void theyReadTheirHardConstraintsAuditLog() {
    context.setLastResponse(context.api().get(HARD_CONSTRAINTS + "/audit-log"));
  }

  @Then("the hard-constraints audit log is empty for this user")
  public void theHardConstraintsAuditLogIsEmptyForThisUser() {
    assertEmptyPage("hard-constraints audit log");
  }

  @When("an anonymous client reads hard constraints with no session")
  public void anAnonymousClientReadsHardConstraintsWithNoSession() {
    // A FRESH client (its own empty cookie jar) is unambiguously anonymous regardless of any
    // session
    // minted earlier in the scenario — the deny-by-default chain must 401 before any domain logic.
    context.setLastResponse(new ApiClient().get(HARD_CONSTRAINTS));
  }

  @Then("the user's hard constraints are unchanged for this user")
  public void theUsersHardConstraintsAreUnchangedForThisUser() {
    // Tier-1 isolation invariant (PREF-38): an AI delta update must NEVER touch hard constraints.
    // Once a seed path exists the seeded hard constraints (no allergies) must be exactly as set.
    Response read = context.api().get(HARD_CONSTRAINTS);
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("hard-constraints GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getList("allergies", String.class)).isEmpty();
  }

  // ---------------- Tier-2 taste profile ----------------

  @When("they read their taste profile")
  public void theyReadTheirTasteProfile() {
    context.setLastResponse(context.api().get(TASTE_PROFILE));
  }

  @Then("the taste-profile read is rejected as not found")
  public void theTasteProfileReadIsRejectedAsNotFound() {
    assertNotFound("a fresh user has no taste profile (initialise is in-process only)");
  }

  @When("they list their taste-profile versions")
  public void theyListTheirTasteProfileVersions() {
    context.setLastResponse(context.api().get(TASTE_PROFILE + "/versions"));
  }

  @Then("the taste-profile version list is empty for this user")
  public void theTasteProfileVersionListIsEmptyForThisUser() {
    assertEmptyPage("taste-profile version list");
  }

  @When("they request a manual taste-profile refresh")
  public void theyRequestAManualTasteProfileRefresh() {
    // Stash the current version first (if a profile exists) so the async poll can detect the bump.
    Response current = context.api().get(TASTE_PROFILE);
    if (current.statusCode() == 200) {
      context.put(TP_VERSION_BEFORE, current.jsonPath().getInt("documentVersion"));
    }
    // refresh-now is fire-and-forget at the controller (publishes an event, returns 202 ACCEPTED).
    // For a fresh user the profile does not exist, so this 404s (the load-bearing finding).
    Response response =
        context.api().request().body(Map.of()).when().post(TASTE_PROFILE + "/refresh-now");
    context.setLastResponse(response);
  }

  @Then("the taste-profile refresh is rejected as not found")
  public void theTasteProfileRefreshIsRejectedAsNotFound() {
    assertNotFound("refresh-now 404s with no profile to refresh (no HTTP seed path)");
  }

  @Then("the taste profile reaches a new version after the refresh for this user")
  public void theTasteProfileReachesANewVersionAfterTheRefreshForThisUser() {
    // The POST returns 202 ACCEPTED immediately; the AI delta apply runs asynchronously
    // (AFTER_COMMIT → listener → REQUIRES_NEW). Mirror PreferenceDeltaPipelineIT: POLL the profile
    // until documentVersion increments, with a timeout. Do NOT assert the bump synchronously.
    assertThat(context.lastResponse().statusCode())
        .as("refresh-now should be accepted with 202 ACCEPTED")
        .isEqualTo(202);
    int before = context.<Integer>get(TP_VERSION_BEFORE);
    awaitCondition(
        () -> {
          Response read = context.api().get(TASTE_PROFILE);
          return read.statusCode() == 200 && read.jsonPath().getInt("documentVersion") > before;
        },
        REFRESH_TIMEOUT);

    Response after = context.api().get(TASTE_PROFILE);
    context.setLastResponse(after);
    assertThat(after.jsonPath().getInt("documentVersion")).isGreaterThan(before);
    // The applied delta (ADD prawns to likes.ingredients) must be reflected in the document. The
    // document is a nested object; serialise the whole node to text and assert the new item lands
    // (the e2e-stub deserialises the canned JSON through the REAL ObjectMapper into the wire type,
    // so this asserts the genuine delta-apply, not the stub).
    assertThat(after.jsonPath().getMap("document").toString().toLowerCase()).contains("prawns");
  }

  @When("they manually override a taste-profile item")
  public void theyManuallyOverrideATasteProfileItem() {
    // A manual override PUT replaces the document; expectedVersion echoes the profile's optimistic
    // version. For a fresh user the profile does not exist, so this 404s. The real body is built
    // from the current profile once a seed path exists; here we send a minimal shape so the glue is
    // exercised end-to-end the moment that happens.
    Response current = context.api().get(TASTE_PROFILE);
    long expected =
        current.statusCode() == 200 ? current.jsonPath().getLong("optimisticVersion") : 0L;
    Map<String, Object> document =
        current.statusCode() == 200 ? current.jsonPath().getMap("document") : Map.of();
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("document", document);
    body.put("expectedVersion", expected);
    context.setLastResponse(context.api().request().body(body).when().put(TASTE_PROFILE));
  }

  @Then("the override creates a new taste-profile version for this user")
  public void theOverrideCreatesANewTasteProfileVersionForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("manual override PUT should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getInt("documentVersion")).isGreaterThan(1);
  }

  @When("they roll their taste profile back to version {int}")
  public void theyRollTheirTasteProfileBackToVersion(int targetVersion) {
    Response current = context.api().get(TASTE_PROFILE);
    long expected =
        current.statusCode() == 200 ? current.jsonPath().getLong("optimisticVersion") : 0L;
    Map<String, Object> body =
        Map.of("targetDocumentVersion", targetVersion, "expectedVersion", expected);
    context.setLastResponse(context.api().post(TASTE_PROFILE + "/rollback", body));
  }

  @Then("the rollback creates a new taste-profile version for this user")
  public void theRollbackCreatesANewTasteProfileVersionForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("rollback should return 200 OK").isEqualTo(200);
    // Rollback is monotonic — never a decrement; it lands as a NEW version above the previous.
    assertThat(response.jsonPath().getInt("documentVersion")).isGreaterThan(1);
  }

  // ---------------- Tier-2 preference archive ----------------

  @When("they read their preference archive")
  public void theyReadTheirPreferenceArchive() {
    context.setLastResponse(context.api().get(ARCHIVE));
  }

  @Then("the preference archive is empty for this user")
  public void thePreferenceArchiveIsEmptyForThisUser() {
    assertEmptyPage("preference archive");
  }

  @When("they read their active archive count")
  public void theyReadTheirActiveArchiveCount() {
    context.setLastResponse(context.api().get(ARCHIVE + "/active-count"));
  }

  @Then("the active archive count is zero for this user")
  public void theActiveArchiveCountIsZeroForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("active-count GET should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getLong("count")).isZero();
  }

  // ---------------- Tier-3 lifestyle config ----------------

  @When("they read their lifestyle config")
  public void theyReadTheirLifestyleConfig() {
    context.setLastResponse(context.api().get(LIFESTYLE_CONFIG));
  }

  @Then("the lifestyle-config read is rejected as not found")
  public void theLifestyleConfigReadIsRejectedAsNotFound() {
    assertNotFound("a fresh user has no lifestyle config (initialise is in-process only)");
  }

  @When("they edit a lifestyle config setting")
  public void theyEditALifestyleConfigSetting() {
    // A lifestyle PUT 404s until initialise() has run (initialise is the onboarding wizard's
    // in-process call, never on the REST surface). We send a minimal valid full-replacement
    // document.
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("document", lifestyleDocument());
    context.setLastResponse(context.api().request().body(body).when().put(LIFESTYLE_CONFIG));
  }

  @Then("the lifestyle-config update is rejected as not found")
  public void theLifestyleConfigUpdateIsRejectedAsNotFound() {
    assertNotFound(
        "a lifestyle PUT 404s until initialise has run (not exposed on the REST surface)");
  }

  @Then("the lifestyle setting is stored and reflected on a read for this user")
  public void theLifestyleSettingIsStoredAndReflectedOnAReadForThisUser() {
    Response put = context.lastResponse();
    assertThat(put.statusCode()).as("lifestyle PUT should return 200 OK").isEqualTo(200);
    assertThat(put.jsonPath().getString("userId")).isEqualTo(context.userId());

    Response read = context.api().get(LIFESTYLE_CONFIG);
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("lifestyle GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("userId")).isEqualTo(context.userId());
  }

  @And("the lifestyle-config audit log records the change for this user")
  public void theLifestyleConfigAuditLogRecordsTheChangeForThisUser() {
    Response audit = context.api().get(LIFESTYLE_CONFIG + "/audit-log");
    context.setLastResponse(audit);
    assertThat(audit.statusCode())
        .as("lifestyle audit-log GET should return 200 OK")
        .isEqualTo(200);
    assertThat(audit.jsonPath().getList("content")).isNotEmpty();
  }

  @When("they mark their lifestyle config as reviewed")
  public void theyMarkTheirLifestyleConfigAsReviewed() {
    context.setLastResponse(context.api().post(LIFESTYLE_CONFIG + "/mark-reviewed", Map.of()));
  }

  @Then("the review nudge is reset for this user")
  public void theReviewNudgeIsResetForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("mark-reviewed should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
  }

  @When("they read their lifestyle-config audit log")
  public void theyReadTheirLifestyleConfigAuditLog() {
    context.setLastResponse(context.api().get(LIFESTYLE_CONFIG + "/audit-log"));
  }

  @Then("the lifestyle-config audit log is empty for this user")
  public void theLifestyleConfigAuditLogIsEmptyForThisUser() {
    assertEmptyPage("lifestyle-config audit log");
  }

  // ---------------- helpers ----------------

  private Response putHardConstraints(Map<String, Object> body) {
    return context.api().request().body(body).when().put(HARD_CONSTRAINTS);
  }

  /** A valid full-replacement hard-constraints PUT body (the @NotNull collections are present). */
  private static Map<String, Object> hardConstraintsBody(
      List<String> allergies, long expectedVersion) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("allergies", new java.util.ArrayList<>(allergies));
    body.put(
        "dietaryIdentity",
        new java.util.HashMap<>(
            Map.of("base", "omnivore", "labelForDisplay", "omnivore", "exceptions", List.of())));
    body.put("medicalDiets", List.of());
    body.put("intolerances", List.of());
    body.put("ageRestrictions", List.of());
    body.put("expectedVersion", expectedVersion);
    return body;
  }

  /**
   * A minimal valid lifestyle document (all sections null is the seed shape; PUT is a replacement).
   */
  private static Map<String, Object> lifestyleDocument() {
    Map<String, Object> doc = new java.util.HashMap<>();
    doc.put("mealStructure", null);
    doc.put("mealTiming", null);
    doc.put("noveltyTolerance", null);
    doc.put("cookingContexts", null);
    doc.put("batchCooking", null);
    doc.put("reheatingPreferences", null);
    doc.put("eatingContext", null);
    doc.put("seasonalPreferences", null);
    doc.put("mealTypePreferences", null);
    doc.put("accompaniments", null);
    doc.put("groceryQualityPreferences", null);
    doc.put("pantryTracking", null);
    return doc;
  }

  private void assertNotFound(String why) {
    assertThat(context.lastResponse().statusCode())
        .as(why + " — expected 404 Not Found")
        .isEqualTo(404);
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
   * Poll {@code check} until it passes or {@code timeout} elapses (mirrors {@code
   * PreferenceDeltaPipelineIT.awaitCondition}) — the async {@code refresh-now} apply must not be
   * asserted synchronously right after the POST.
   */
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
        throw new IllegalStateException("Interrupted while awaiting taste-profile refresh", ie);
      }
    }
    if (!check.getAsBoolean()) {
      throw new AssertionError("Timed out waiting for taste-profile version bump after " + timeout);
    }
  }
}
