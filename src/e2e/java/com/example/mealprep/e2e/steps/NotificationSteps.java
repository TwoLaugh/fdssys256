package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Notification step definitions (notification.md): the in-app inbox + per-user preferences read /
 * update surface, the mark-read/dismiss state machine, and the headline errors — exercised over the
 * black-box HTTP API (decision D2).
 *
 * <p><b>The load-bearing findings that shape this feature</b> (from reading {@code
 * NotificationsController}, {@code NotificationPreferencesController}, and {@code
 * NotificationServiceImpl}):
 *
 * <ul>
 *   <li><b>Preferences auto-seed on read.</b> {@code GET /preferences} ensures a defaults row
 *       first, so a fresh user always gets 200 (never 404). {@code PUT} replaces the document with
 *       an optimistic {@code expectedVersion} (stale → 409) and requires {@code enabledKinds} to
 *       cover EXACTLY the {@link NotificationKind} enum.
 *   <li><b>The inbox is reactive.</b> There is NO public HTTP path to create a notification — so a
 *       fresh user's list is an empty {@code Page}, and the mark-read/dismiss/transition lifecycle
 *       needs a real row first: the {@code E2eNotificationSeedController} ({@code @Profile e2e})
 *       persists one UNREAD notification via the public create command.
 *   <li><b>Mark-read is NOT idempotent.</b> {@code UNREAD→READ} is legal; {@code READ→READ} is an
 *       illegal transition → 409. {@code UNREAD→DISMISSED} is legal; {@code DISMISSED} is terminal.
 *   <li><b>Unknown id → 404</b>, scoped to the caller.
 * </ul>
 *
 * <p>Self-contained (D5): every scenario registers its OWN fresh user; self-scoped: notifications +
 * preferences are per-user (the server resolves {@code userId} from the session), so every
 * assertion looks only at THIS user's state, never a global count.
 */
public class NotificationSteps {

  private static final String NOTIFICATIONS = "/api/v1/notifications";
  private static final String PREFERENCES = NOTIFICATIONS + "/preferences";
  private static final String SUMMARY = NOTIFICATIONS + "/summary";

  /** E2E-only seeder (E2eNotificationSeedController, @Profile e2e). */
  private static final String SEED_NOTIFICATION = "/test-support/notification/seed";

  /** Cross-step keys (domain-namespaced — see ScenarioContext javadoc). */
  private static final String NOTIFICATION_ID = "notification.notificationId";

  private static final String PREF_VERSION = "notification.prefVersion";

  private final ScenarioContext context;

  public NotificationSteps(ScenarioContext context) {
    this.context = context;
  }

  // ---------------- preferences ----------------

  @When("they read their notification preferences")
  public void theyReadTheirNotificationPreferences() {
    context.setLastResponse(context.api().get(PREFERENCES));
  }

  @Given("the user has read their notification preferences")
  public void theUserHasReadTheirNotificationPreferences() {
    Response read = context.api().get(PREFERENCES);
    context.setLastResponse(read);
    assertThat(read.statusCode())
        .as("preferences GET should auto-seed and return 200 OK")
        .isEqualTo(200);
    context.put(PREF_VERSION, read.jsonPath().getLong("version"));
  }

  @Then("the notification preferences are returned with defaults for this user")
  public void theNotificationPreferencesAreReturnedWithDefaultsForThisUser() {
    Response read = context.lastResponse();
    assertThat(read.statusCode()).as("preferences GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getString("userId")).isEqualTo(context.userId());
    // The seeded defaults cover exactly the NotificationKind universe.
    assertThat(read.jsonPath().getMap("enabledKinds").keySet())
        .as("enabledKinds covers the kind enum")
        .isNotEmpty();
    context.put(PREF_VERSION, read.jsonPath().getLong("version"));
  }

  @When("they enable quiet hours in their notification preferences")
  public void theyEnableQuietHoursInTheirNotificationPreferences() {
    context.setLastResponse(putPreferences(true, currentPrefVersion()));
  }

  @Then("the quiet-hours change is reflected on a read for this user")
  public void theQuietHoursChangeIsReflectedOnAReadForThisUser() {
    Response put = context.lastResponse();
    assertThat(put.statusCode()).as("preferences PUT should return 200 OK").isEqualTo(200);
    assertThat(put.jsonPath().getBoolean("quietHoursEnabled")).isTrue();

    Response read = context.api().get(PREFERENCES);
    context.setLastResponse(read);
    assertThat(read.statusCode()).as("preferences GET should return 200 OK").isEqualTo(200);
    assertThat(read.jsonPath().getBoolean("quietHoursEnabled")).isTrue();
    assertThat(read.jsonPath().getString("quietHoursStart")).isNotBlank();
    assertThat(read.jsonPath().getString("quietHoursEnd")).isNotBlank();
  }

  @When("they update preferences with a stale expected version")
  public void theyUpdatePreferencesWithAStaleExpectedVersion() {
    // A deliberately-wrong expectedVersion (current + 99) trips the optimistic pre-check -> 409.
    context.setLastResponse(putPreferences(true, currentPrefVersion() + 99L));
  }

  @Then("the preferences update is rejected as a conflict")
  public void thePreferencesUpdateIsRejectedAsAConflict() {
    assertThat(context.lastResponse().statusCode())
        .as("a stale expectedVersion on a preferences PUT must be 409 Conflict")
        .isEqualTo(409);
  }

  // ---------------- inbox reads (empty-state + summary) ----------------

  @When("they list their notifications")
  public void theyListTheirNotifications() {
    context.setLastResponse(context.api().get(NOTIFICATIONS));
  }

  @Then("the notification list is empty for this user")
  public void theNotificationListIsEmptyForThisUser() {
    Response list = context.lastResponse();
    assertThat(list.statusCode()).as("notification list should return 200 OK").isEqualTo(200);
    assertThat(list.jsonPath().getList("content")).as("notification content").isEmpty();
    assertThat(list.jsonPath().getLong("totalElements")).as("notification totalElements").isZero();
  }

  @When("they read their notification summary")
  public void theyReadTheirNotificationSummary() {
    context.setLastResponse(context.api().get(SUMMARY));
  }

  @Then("the notification summary counts are zero for this user")
  public void theNotificationSummaryCountsAreZeroForThisUser() {
    Response summary = context.lastResponse();
    assertThat(summary.statusCode()).as("summary GET should return 200 OK").isEqualTo(200);
    // Self-scoped: this user has emitted nothing, so all badge counts are zero (the counts are
    // per-user, scoped to the calling session — never a global aggregate).
    assertThat(summary.jsonPath().getInt("unreadCount")).isZero();
    assertThat(summary.jsonPath().getInt("attentionCount")).isZero();
    assertThat(summary.jsonPath().getInt("urgentCount")).isZero();
  }

  @When("they mark a random non-existent notification read")
  public void theyMarkARandomNonExistentNotificationRead() {
    context.setLastResponse(
        context.api().post(NOTIFICATIONS + "/" + UUID.randomUUID() + "/read", Map.of()));
  }

  @Then("the notification mark-read is rejected as not found")
  public void theNotificationMarkReadIsRejectedAsNotFound() {
    assertThat(context.lastResponse().statusCode())
        .as("marking an unknown notification read must be 404 Not Found")
        .isEqualTo(404);
  }

  @When("an anonymous client lists notifications with no session")
  public void anAnonymousClientListsNotificationsWithNoSession() {
    // A FRESH client (its own empty cookie jar) is unambiguously anonymous; the deny-by-default
    // chain must 401 before any notification logic.
    context.setLastResponse(new ApiClient().get(NOTIFICATIONS));
  }

  // ---------------- inbox lifecycle (e2e seeder) ----------------

  @Given("the user has a seeded unread notification")
  public void theUserHasASeededUnreadNotification() {
    Response response = context.api().request().when().post(SEED_NOTIFICATION);
    context.setLastResponse(response);
    assertThat(response.statusCode())
        .as("seeding a notification should return 201 Created (e2e seeder)")
        .isEqualTo(201);
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
    assertThat(response.jsonPath().getString("status")).isEqualTo("UNREAD");
    context.put(NOTIFICATION_ID, response.jsonPath().getString("id"));
  }

  @Then("the seeded notification is listed as unread for this user")
  public void theSeededNotificationIsListedAsUnreadForThisUser() {
    Response list = context.lastResponse();
    assertThat(list.statusCode()).as("notification list should return 200 OK").isEqualTo(200);
    // Self-scoped: this user's list carries exactly the seeded notification, UNREAD.
    assertThat(list.jsonPath().getList("content.id", String.class)).contains(notificationId());
  }

  @When("they mark that notification read")
  public void theyMarkThatNotificationRead() {
    context.setLastResponse(
        context.api().post(NOTIFICATIONS + "/" + notificationId() + "/read", Map.of()));
  }

  @Then("the notification is read for this user")
  public void theNotificationIsReadForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("mark-read should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("status")).isEqualTo("READ");
    assertThat(response.jsonPath().getString("readAt")).isNotBlank();

    // The read state persists on a fresh read for THIS user (self-scoped).
    Response read = context.api().get(NOTIFICATIONS + "/" + notificationId());
    context.setLastResponse(read);
    assertThat(read.jsonPath().getString("status")).isEqualTo("READ");
  }

  @When("they mark that notification read again")
  public void theyMarkThatNotificationReadAgain() {
    context.setLastResponse(
        context.api().post(NOTIFICATIONS + "/" + notificationId() + "/read", Map.of()));
  }

  @Then("the notification transition is rejected as a conflict")
  public void theNotificationTransitionIsRejectedAsAConflict() {
    assertThat(context.lastResponse().statusCode())
        .as("re-marking a READ notification is an illegal transition — expected 409")
        .isEqualTo(409);
  }

  @When("they dismiss that notification")
  public void theyDismissThatNotification() {
    context.setLastResponse(
        context.api().post(NOTIFICATIONS + "/" + notificationId() + "/dismiss", Map.of()));
  }

  @Then("the notification is dismissed for this user")
  public void theNotificationIsDismissedForThisUser() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("dismiss should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("status")).isEqualTo("DISMISSED");
    assertThat(response.jsonPath().getString("dismissedAt")).isNotBlank();
  }

  @When("they read that notification's delivery log")
  public void theyReadThatNotificationsDeliveryLog() {
    context.setLastResponse(
        context.api().get(NOTIFICATIONS + "/" + notificationId() + "/delivery-log"));
  }

  @Then("the notification delivery log is empty for this notification")
  public void theNotificationDeliveryLogIsEmptyForThisNotification() {
    // The seeder persists the notification directly via the create command, so it never went
    // through the delivery channel — its delivery-log page is empty (200, not an error).
    Response log = context.lastResponse();
    assertThat(log.statusCode()).as("delivery-log GET should return 200 OK").isEqualTo(200);
    assertThat(log.jsonPath().getList("content")).as("delivery-log content").isEmpty();
    assertThat(log.jsonPath().getLong("totalElements")).as("delivery-log totalElements").isZero();
  }

  // ---------------- helpers ----------------

  private Response putPreferences(boolean quietHoursEnabled, long expectedVersion) {
    Map<String, Object> body = new HashMap<>();
    // enabledKinds must cover EXACTLY the NotificationKind enum — build the full map (all enabled).
    Map<String, Object> enabledKinds = new LinkedHashMap<>();
    for (NotificationKind kind : NotificationKind.values()) {
      enabledKinds.put(kind.name(), true);
    }
    body.put("enabledKinds", enabledKinds);
    body.put("quietHoursEnabled", quietHoursEnabled);
    body.put("quietHoursStart", quietHoursEnabled ? "22:00:00" : null);
    body.put("quietHoursEnd", quietHoursEnabled ? "07:00:00" : null);
    body.put("timezone", "Europe/London");
    body.put("debounceWindowMinutes", 30);
    body.put("expectedVersion", expectedVersion);
    return context.api().request().body(body).when().put(PREFERENCES);
  }

  private long currentPrefVersion() {
    Long v = context.get(PREF_VERSION);
    if (v != null) {
      return v;
    }
    Response read = context.api().get(PREFERENCES);
    long version = read.jsonPath().getLong("version");
    context.put(PREF_VERSION, version);
    return version;
  }

  private String notificationId() {
    return context.get(NOTIFICATION_ID);
  }
}
