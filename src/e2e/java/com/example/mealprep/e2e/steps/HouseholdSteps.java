package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Household step definitions (household.md): the multi-user composition layer — membership + roles,
 * shared-vs-individual slot settings + audit, and slot-configuration reads — exercised over the
 * black-box HTTP API (decision D2). Household is the green-rich half of Batch 3: it has real
 * POST/PUT/DELETE paths AND seeds a default settings row on create, so the create → read →
 * settings-edit → member-admin lifecycle is buildable with no seeder.
 *
 * <p><b>The load-bearing findings that shape this feature</b> (from reading {@code
 * HouseholdsController}, {@code HouseholdMembersController}, {@code HouseholdSettingsController},
 * and {@code HouseholdServiceImpl}):
 *
 * <ul>
 *   <li><b>Create is real and self-seeding.</b> {@code POST /api/v1/households} makes the caller
 *       the sole {@code primary} member and seeds default slot settings
 *       (breakfast/lunch/dinner/snack, all shared, headcount 1, timeBudget 30) in the same
 *       transaction — so {@code GET /current}, {@code GET /{id}/settings}, {@code GET
 *       /{id}/slot-configuration} all return 200 and the settings audit-log is an empty {@code
 *       Page} for a fresh household.
 *   <li><b>Single household per user.</b> A second create by the same user is 409.
 *   <li><b>A second member is assemblable in one scenario.</b> {@code POST /current/members} takes
 *       a TARGET {@code userId} in the body; this glue registers a second account on a SEPARATE
 *       {@link ApiClient} (a fresh cookie jar) only to mint its {@code userId}, then the primary
 *       adds it — no second interactive login disturbs the primary's session.
 *   <li><b>Transition errors are real.</b> Demoting the sole primary while another member remains
 *       is a 409 ({@code LastPrimaryRemoval}); a stale {@code expectedVersion} on a settings PUT is
 *       409.
 * </ul>
 *
 * <p>Self-contained (D5): every scenario registers its OWN fresh primary; self-scoped: assertions
 * look only at THIS household's roster / settings, never a global count.
 */
public class HouseholdSteps {

  private static final String HOUSEHOLDS = "/api/v1/households";
  private static final String CURRENT = HOUSEHOLDS + "/current";
  private static final String CURRENT_MEMBERS = CURRENT + "/members";

  /** Cross-step keys (domain-namespaced — see ScenarioContext javadoc). */
  private static final String HOUSEHOLD_ID = "household.householdId";

  private static final String HOUSEHOLD_NAME = "household.householdName";
  private static final String SETTINGS_VERSION = "household.settingsVersion";
  private static final String SECOND_USER_ID = "household.secondUserId";
  private static final String SECOND_MEMBER_ID = "household.secondMemberId";
  private static final String SELF_MEMBER_ID = "household.selfMemberId";
  private static final String SELF_MEMBER_VERSION = "household.selfMemberVersion";

  private final ScenarioContext context;

  public HouseholdSteps(ScenarioContext context) {
    this.context = context;
  }

  // ---------------- create ----------------

  @When("they create a household")
  public void theyCreateAHousehold() {
    String name = "E2E Household " + shortId();
    context.put(HOUSEHOLD_NAME, name);
    context.setLastResponse(context.api().post(HOUSEHOLDS, Map.of("name", name)));
  }

  @Given("the user has created a household")
  public void theUserHasCreatedAHousehold() {
    String name = "E2E Household " + shortId();
    context.put(HOUSEHOLD_NAME, name);
    Response response = context.api().post(HOUSEHOLDS, Map.of("name", name));
    context.setLastResponse(response);
    assertThat(response.statusCode())
        .as("household create should return 201 Created")
        .isEqualTo(201);
    rememberHousehold(response);
  }

  @When("they create another household")
  public void theyCreateAnotherHousehold() {
    context.setLastResponse(
        context.api().post(HOUSEHOLDS, Map.of("name", "E2E Second " + shortId())));
  }

  @When("they create a household with a blank name")
  public void theyCreateAHouseholdWithABlankName() {
    // @NotBlank name — a blank string is a synchronous 400.
    context.setLastResponse(context.api().post(HOUSEHOLDS, Map.of("name", "  ")));
  }

  @When("an anonymous client creates a household with no session")
  public void anAnonymousClientCreatesAHouseholdWithNoSession() {
    // A FRESH client (its own empty cookie jar) is unambiguously anonymous; the deny-by-default
    // chain must 401 before any household logic.
    context.setLastResponse(new ApiClient().post(HOUSEHOLDS, Map.of("name", "E2E Anon")));
  }

  @Then("the household is created with this user as its primary member")
  public void theHouseholdIsCreatedWithThisUserAsItsPrimaryMember() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("household create should return 201 Created")
        .isEqualTo(201);
    assertThat(response.jsonPath().getString("id"))
        .as("create must mint a household id")
        .isNotBlank();
    assertThat(response.jsonPath().getString("createdByUserId")).isEqualTo(context.userId());
    // The creator is the sole member, with role primary.
    assertThat(response.jsonPath().getList("members")).hasSize(1);
    assertThat(response.jsonPath().getString("members[0].userId")).isEqualTo(context.userId());
    assertThat(response.jsonPath().getString("members[0].role")).isEqualTo("primary");
    rememberHousehold(response);
  }

  @Then("the second household creation is rejected as a conflict")
  public void theSecondHouseholdCreationIsRejectedAsAConflict() {
    assertConflict("a user may belong to only one household — a second create must be 409");
  }

  @Then("the household creation is rejected as a validation error")
  public void theHouseholdCreationIsRejectedAsAValidationError() {
    assertThat(context.lastResponse().statusCode())
        .as("a blank household name must be rejected with 400 Bad Request")
        .isEqualTo(400);
  }

  // ---------------- roster reads ----------------

  @When("they read their current household")
  public void theyReadTheirCurrentHousehold() {
    context.setLastResponse(context.api().get(CURRENT));
  }

  @Then("the household roster lists only this user as primary")
  public void theHouseholdRosterListsOnlyThisUserAsPrimary() {
    Response read = context.lastResponse();
    assertThat(read.statusCode()).as("current-household GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getList("members.userId", String.class))
        .containsExactly(context.userId());
    assertThat(read.jsonPath().getString("members[0].role")).isEqualTo("primary");
  }

  @Then("the current-household read is rejected as not found")
  public void theCurrentHouseholdReadIsRejectedAsNotFound() {
    assertNotFound("a user with no household must get 404 on GET /current");
  }

  // ---------------- settings + slot configuration ----------------

  @When("they read their household settings")
  public void theyReadTheirHouseholdSettings() {
    context.setLastResponse(context.api().get(HOUSEHOLDS + "/" + householdId() + "/settings"));
  }

  @Then("the household settings carry the four default meal slots for this household")
  public void theHouseholdSettingsCarryTheFourDefaultMealSlotsForThisHousehold() {
    Response read = context.lastResponse();
    assertThat(read.statusCode()).as("settings GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("householdId")).isEqualTo(householdId());
    // The create path seeds breakfast/lunch/dinner/snack defaults.
    assertThat(read.jsonPath().getMap("document.slotDefaults").keySet())
        .contains("breakfast", "lunch", "dinner", "snack");
    rememberSettingsVersion(read);
  }

  @When("they read their household slot configuration")
  public void theyReadTheirHouseholdSlotConfiguration() {
    context.setLastResponse(
        context.api().get(HOUSEHOLDS + "/" + householdId() + "/slot-configuration"));
  }

  @Then("the slot configuration lists the default meal slots for this household")
  public void theSlotConfigurationListsTheDefaultMealSlotsForThisHousehold() {
    Response read = context.lastResponse();
    assertThat(read.statusCode()).as("slot-configuration GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("householdId")).isEqualTo(householdId());
    // One resolved entry per built-in slot kind; the sole eater is this user.
    assertThat(read.jsonPath().getList("slots.slotKey", String.class))
        .contains("breakfast", "lunch", "dinner", "snack");
    assertThat(read.jsonPath().getList("allEaterUserIds", String.class)).contains(context.userId());
  }

  @When("they read their household settings audit log")
  public void theyReadTheirHouseholdSettingsAuditLog() {
    context.setLastResponse(
        context.api().get(HOUSEHOLDS + "/" + householdId() + "/settings/audit-log"));
  }

  @Then("the household settings audit log is empty for this household")
  public void theHouseholdSettingsAuditLogIsEmptyForThisHousehold() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("settings audit-log GET should return 200 OK")
        .isEqualTo(200);
    assertThat(response.jsonPath().getList("content")).as("audit log should be empty").isEmpty();
    assertThat(response.jsonPath().getLong("totalElements")).as("audit totalElements").isZero();
  }

  @When("they mark the dinner slot as not shared")
  public void theyMarkTheDinnerSlotAsNotShared() {
    context.setLastResponse(putSettings(dinnerNotSharedDocument(), currentSettingsVersion()));
  }

  @Then("the slot setting change is reflected on a read for this household")
  public void theSlotSettingChangeIsReflectedOnAReadForThisHousehold() {
    Response put = context.lastResponse();
    assertThat(put.statusCode()).as("settings PUT should return 200 OK").isEqualTo(200);
    assertThat(put.jsonPath().getBoolean("document.slotDefaults.dinner.shared")).isFalse();
    rememberSettingsVersion(put);

    Response read = context.api().get(HOUSEHOLDS + "/" + householdId() + "/settings");
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("settings GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getBoolean("document.slotDefaults.dinner.shared")).isFalse();
  }

  @And("the household settings audit log records the change for this household")
  public void theHouseholdSettingsAuditLogRecordsTheChangeForThisHousehold() {
    Response audit = context.api().get(HOUSEHOLDS + "/" + householdId() + "/settings/audit-log");
    context.setLastResponse(audit);
    assertThat(audit.statusCode()).as("settings audit-log GET should return 200 OK").isEqualTo(200);
    // Self-scoped: this household's audit log now carries at least the dinner-slot change row.
    assertThat(audit.jsonPath().getList("content")).isNotEmpty();
    assertThat(audit.jsonPath().getList("content.actorUserId", String.class))
        .contains(context.userId());
  }

  @When("they update settings with a stale expected version")
  public void theyUpdateSettingsWithAStaleExpectedVersion() {
    // A deliberately-wrong expectedVersion (current + 99) trips the optimistic pre-check -> 409.
    context.setLastResponse(putSettings(dinnerNotSharedDocument(), currentSettingsVersion() + 99L));
  }

  @Then("the settings update is rejected as a conflict")
  public void theSettingsUpdateIsRejectedAsAConflict() {
    assertConflict("a stale expectedVersion on a settings PUT must be 409 Conflict");
  }

  @When("they read settings for a random non-existent household")
  public void theyReadSettingsForARandomNonExistentHousehold() {
    context.setLastResponse(context.api().get(HOUSEHOLDS + "/" + UUID.randomUUID() + "/settings"));
  }

  @Then("the household settings read is rejected as not found")
  public void theHouseholdSettingsReadIsRejectedAsNotFound() {
    assertNotFound("settings for an unknown / non-member household must be 404");
  }

  // ---------------- membership ----------------

  @And("a second user account exists")
  public void aSecondUserAccountExists() {
    // Register a second account on a SEPARATE ApiClient (its own cookie jar) purely to mint its
    // userId — the primary's session in context.api() is untouched. The userId is then used as the
    // ADD target on the primary's session.
    String username = CommonSteps.randomUsername();
    Response response =
        new ApiClient()
            .post(
                "/api/v1/auth/register",
                Map.of("username", username, "password", CommonSteps.VALID_PASSWORD));
    assertThat(response.statusCode())
        .as("second-user setup: register should return 201 Created")
        .isEqualTo(201);
    String secondUserId = response.jsonPath().getString("userId");
    assertThat(secondUserId).as("register must mint a canonical user id").isNotBlank();
    context.put(SECOND_USER_ID, secondUserId);
  }

  @When("they add the second user as a member")
  public void theyAddTheSecondUserAsAMember() {
    context.setLastResponse(addSecondMember());
  }

  @And("the primary has added the second user as a member")
  public void thePrimaryHasAddedTheSecondUserAsAMember() {
    Response response = addSecondMember();
    context.setLastResponse(response);
    assertThat(response.statusCode())
        .as("adding a member should return 201 Created")
        .isEqualTo(201);
    context.put(SECOND_MEMBER_ID, response.jsonPath().getString("id"));
  }

  @Then("the household roster includes the second member for this household")
  public void theHouseholdRosterIncludesTheSecondMemberForThisHousehold() {
    Response add = context.lastResponse();
    assertThat(add.statusCode()).as("adding a member should return 201 Created").isEqualTo(201);
    context.put(SECOND_MEMBER_ID, add.jsonPath().getString("id"));

    Response roster = context.api().get(CURRENT);
    context.setLastResponse(roster);
    assertThat(roster.statusCode()).as("current-household GET should return 200 OK").isEqualTo(200);
    assertThat(roster.jsonPath().getList("members.userId", String.class))
        .contains(context.userId(), context.<String>get(SECOND_USER_ID));
  }

  @When("they remove the second member")
  public void theyRemoveTheSecondMember() {
    context.setLastResponse(
        context.api().request().when().delete(CURRENT_MEMBERS + "/" + secondMemberId()));
  }

  @Then("the household roster no longer includes the second member for this household")
  public void theHouseholdRosterNoLongerIncludesTheSecondMemberForThisHousehold() {
    Response delete = context.lastResponse();
    assertThat(delete.statusCode())
        .as("removing a member should return 204 No Content")
        .isEqualTo(204);

    Response roster = context.api().get(CURRENT);
    context.setLastResponse(roster);
    assertThat(roster.statusCode()).as("current-household GET should return 200 OK").isEqualTo(200);
    assertThat(roster.jsonPath().getList("members.userId", String.class))
        .doesNotContain(context.<String>get(SECOND_USER_ID));
    assertThat(roster.jsonPath().getList("members.userId", String.class))
        .contains(context.userId());
  }

  @When("they demote themselves while another member remains")
  public void theyDemoteThemselvesWhileAnotherMemberRemains() {
    // Resolve THIS user's own member id + version from the roster, then attempt to demote the sole
    // primary to ordinary while a second member is present — the >=1-primary invariant rejects 409.
    Response roster = context.api().get(CURRENT);
    List<Map<String, Object>> members = roster.jsonPath().getList("members");
    Map<String, Object> self =
        members.stream()
            .filter(m -> context.userId().equals(String.valueOf(m.get("userId"))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("primary self not found in roster"));
    context.put(SELF_MEMBER_ID, String.valueOf(self.get("id")));
    context.put(SELF_MEMBER_VERSION, ((Number) self.get("version")).longValue());

    Map<String, Object> body =
        Map.of("newRole", "member", "expectedVersion", context.<Long>get(SELF_MEMBER_VERSION));
    context.setLastResponse(
        context
            .api()
            .post(CURRENT_MEMBERS + "/" + context.<String>get(SELF_MEMBER_ID) + "/role", body));
  }

  @Then("the role change is rejected as a conflict")
  public void theRoleChangeIsRejectedAsAConflict() {
    assertConflict("demoting the last primary while others remain must be 409");
  }

  // ---------------- @pending glue (authored, exercised only by @pending scenarios) -----------

  @When("the primary creates an invite for the second user")
  public void thePrimaryCreatesAnInviteForTheSecondUser() {
    // Needs the second user's session to accept — see the @pending reason in the feature.
    Map<String, Object> body =
        new HashMap<>(
            Map.of(
                "issuedForUserId", context.<String>get(SECOND_USER_ID),
                "intendedRole", "member",
                "expiresAt", java.time.Instant.now().plusSeconds(86400).toString()));
    context.setLastResponse(context.api().post(CURRENT + "/invites", body));
  }

  @And("the second user accepts that invite")
  public void theSecondUserAcceptsThatInvite() {
    // Would require the second user's own authenticated session (a second cookie jar with a logged-
    // in session) — not assemblable under the single-ApiClient-per-scenario model. @pending.
    String code = context.lastResponse().jsonPath().getString("inviteCode");
    context.setLastResponse(
        context
            .api()
            .post("/api/v1/invites/accept", Map.of("inviteCode", code == null ? "x" : code)));
  }

  // ---------------- helpers ----------------

  private Response addSecondMember() {
    Map<String, Object> body =
        new HashMap<>(Map.of("userId", context.<String>get(SECOND_USER_ID), "role", "member"));
    return context.api().post(CURRENT_MEMBERS, body);
  }

  private Response putSettings(Map<String, Object> document, long expectedVersion) {
    Map<String, Object> body = new HashMap<>();
    body.put("document", document);
    body.put("expectedVersion", expectedVersion);
    return context
        .api()
        .request()
        .body(body)
        .when()
        .put(HOUSEHOLDS + "/" + householdId() + "/settings");
  }

  /**
   * The seeded default settings document with the dinner slot flipped to {@code shared=false} — a
   * single-section change so the differ writes exactly one audit row. Mirrors {@code
   * HouseholdServiceImpl.buildDefaultSettings} (breakfast/lunch/snack stay shared, headcount 1,
   * timeBudget 30).
   */
  private static Map<String, Object> dinnerNotSharedDocument() {
    Map<String, Object> slotDefaults = new java.util.LinkedHashMap<>();
    slotDefaults.put("breakfast", slotDefault(true));
    slotDefaults.put("lunch", slotDefault(true));
    slotDefaults.put("dinner", slotDefault(false));
    slotDefaults.put("snack", slotDefault(true));
    Map<String, Object> document = new HashMap<>();
    document.put("slotDefaults", slotDefaults);
    document.put("customSlots", List.of());
    document.put("defaultHeadcount", null);
    document.put("scheduling", new HashMap<>());
    return document;
  }

  private static Map<String, Object> slotDefault(boolean shared) {
    Map<String, Object> m = new HashMap<>();
    m.put("shared", shared);
    m.put("headcount", 1);
    m.put("timeBudgetMin", 30);
    return m;
  }

  private void rememberHousehold(Response response) {
    context.put(HOUSEHOLD_ID, response.jsonPath().getString("id"));
  }

  private void rememberSettingsVersion(Response response) {
    context.put(SETTINGS_VERSION, response.jsonPath().getLong("version"));
  }

  private long currentSettingsVersion() {
    Long v = context.get(SETTINGS_VERSION);
    if (v != null) {
      return v;
    }
    // Read the current version if a prior step did not stash one.
    Response read = context.api().get(HOUSEHOLDS + "/" + householdId() + "/settings");
    long version = read.jsonPath().getLong("version");
    context.put(SETTINGS_VERSION, version);
    return version;
  }

  private String householdId() {
    return context.get(HOUSEHOLD_ID);
  }

  private String secondMemberId() {
    return context.get(SECOND_MEMBER_ID);
  }

  private void assertNotFound(String why) {
    assertThat(context.lastResponse().statusCode()).as(why + " — expected 404").isEqualTo(404);
  }

  private void assertConflict(String why) {
    assertThat(context.lastResponse().statusCode()).as(why + " — expected 409").isEqualTo(409);
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
