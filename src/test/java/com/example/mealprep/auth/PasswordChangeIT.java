package com.example.mealprep.auth;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.PasswordChangeRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.repository.LoginAttemptRepository;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.event.UserPasswordChangedEvent;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full HTTP flow for password change. Covers the four behaviour bullets called out in the ticket:
 * happy path + cookie reissue + old-cookie rejection, wrong currentPassword → 401, weak new
 * password → 400 with errors[], bulk revoke of other sessions, and event emission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  PasswordChangeIT.EventCollector.class
})
@ActiveProfiles("test")
class PasswordChangeIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private LoginAttemptRepository loginAttemptRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private EventCollector eventCollector;

  @AfterEach
  void cleanup() {
    loginAttemptRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCollector.clear();
  }

  // ---------------- Helpers ----------------

  private record RegisteredFixture(String username, UUID userId, Cookie cookie) {}

  private RegisteredFixture register(String password) throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username, password);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    UUID userId =
        userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow().getId();
    return new RegisteredFixture(username, userId, cookie);
  }

  private Cookie loginAgain(String username, String password) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getCookie(authProperties.cookieName());
  }

  private String body(String currentPassword, String newPassword) throws Exception {
    return objectMapper.writeValueAsString(new PasswordChangeRequest(currentPassword, newPassword));
  }

  // ---------------- Tests ----------------

  @Test
  void changePassword_happyPath_returns200_reissuesCookie_oldCookieRejected_newCookieWorks()
      throws Exception {
    String oldPwd = AuthTestData.DEFAULT_PASSWORD;
    String newPwd = "fresh-new-password-12345";
    RegisteredFixture fixture = register(oldPwd);

    MvcResult result =
        mvc.perform(
                put("/api/v1/auth/password")
                    .cookie(fixture.cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(oldPwd, newPwd)))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.userId").value(fixture.userId.toString()))
            .andExpect(jsonPath("$.username").value(fixture.username))
            .andExpect(cookie().exists(authProperties.cookieName()))
            .andExpect(cookie().httpOnly(authProperties.cookieName(), true))
            .andExpect(cookie().path(authProperties.cookieName(), "/"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    Cookie reissued = result.getResponse().getCookie(authProperties.cookieName());
    assertThat(reissued).isNotNull();
    // The cookie value (raw token) is fresh — not byte-equal to the old cookie.
    assertThat(reissued.getValue()).isNotEqualTo(fixture.cookie.getValue());

    // Old cookie is rejected on the next request.
    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie)).andExpect(status().isUnauthorized());

    // New cookie succeeds on the next request.
    mvc.perform(get("/api/v1/auth/me").cookie(reissued))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(fixture.userId.toString()));
  }

  @Test
  void changePassword_returns401_genericMessage_forWrongCurrentPassword() throws Exception {
    String oldPwd = AuthTestData.DEFAULT_PASSWORD;
    RegisteredFixture fixture = register(oldPwd);

    mvc.perform(
            put("/api/v1/auth/password")
                .cookie(fixture.cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("the-wrong-current-password", "fresh-new-password-12345")))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").value("Invalid credentials"))
        // No re-issue on failure — the existing cookie stays valid.
        .andExpect(header().doesNotExist("Set-Cookie"));

    // Old cookie still works because nothing was rotated.
    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie)).andExpect(status().isOk());
  }

  @Test
  void changePassword_returns400_withErrors_forWeakNewPassword() throws Exception {
    String oldPwd = AuthTestData.DEFAULT_PASSWORD;
    RegisteredFixture fixture = register(oldPwd);

    mvc.perform(
            put("/api/v1/auth/password")
                .cookie(fixture.cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(oldPwd, "short")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')]").exists());
  }

  @Test
  void wrongCurrentPasswordOnPasswordChange_countsTowardLoginThrottle() throws Exception {
    // auth-2: a wrong current-password on PUT /password records a BAD_PASSWORD LoginAttempt (in a
    // committed tx via noRollbackFor) so PUT /password shares the login throttle surface. Drive the
    // per-username failure threshold entirely through PUT /password, then prove a LOGIN for the
    // same
    // username is throttled (429) — the two endpoints share one throttle window.
    String oldPwd = AuthTestData.DEFAULT_PASSWORD;
    RegisteredFixture fixture = register(oldPwd);

    int threshold = authProperties.throttle().usernameMaxFailures();
    for (int i = 0; i < threshold; i++) {
      mvc.perform(
              put("/api/v1/auth/password")
                  .cookie(fixture.cookie)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("the-wrong-current-password", "fresh-new-password-12345")))
          .andExpect(status().isUnauthorized());
    }

    // Each wrong-current PUT committed a BAD_PASSWORD attempt for this username.
    assertThat(
            loginAttemptRepository.findAll().stream()
                .filter(a -> a.getUsernameNormalised().equals(fixture.username.toLowerCase()))
                .filter(a -> a.getFailureReason() == LoginFailureReason.BAD_PASSWORD)
                .count())
        .isGreaterThanOrEqualTo(threshold);

    // A login for the same username now trips the SAME per-username throttle → 429.
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new LoginRequest(fixture.username, oldPwd))))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void changePassword_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            put("/api/v1/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(AuthTestData.DEFAULT_PASSWORD, "fresh-new-password-12345")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void changePassword_bulkRevokesOtherSessions_butNewCallingCookieStillWorks() throws Exception {
    String oldPwd = AuthTestData.DEFAULT_PASSWORD;
    String newPwd = "fresh-new-password-12345";
    RegisteredFixture fixture = register(oldPwd);

    // "Browser A" — the cookie returned from register.
    Cookie cookieA = fixture.cookie;
    // "Browser B" and "Browser C" — second and third logins of the same user. Three active
    // sessions in total (register + 2 logins) — gives us a sessionsRevokedCount > 1 to assert on.
    Cookie cookieB = loginAgain(fixture.username, oldPwd);
    Cookie cookieC = loginAgain(fixture.username, oldPwd);
    assertThat(cookieB).isNotNull();
    assertThat(cookieC).isNotNull();
    assertThat(cookieB.getValue()).isNotEqualTo(cookieA.getValue());
    assertThat(cookieC.getValue()).isNotEqualTo(cookieB.getValue());
    long activeBeforeChange =
        sessionRepository.findByUserIdAndRevokedAtIsNull(fixture.userId).size();
    assertThat(activeBeforeChange).isEqualTo(3L);

    // Change password from cookieA. Cookie A is reissued; cookies B and C are revoked.
    MvcResult result =
        mvc.perform(
                put("/api/v1/auth/password")
                    .cookie(cookieA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(oldPwd, newPwd)))
            .andExpect(status().isOk())
            .andReturn();
    Cookie cookieAPrime = result.getResponse().getCookie(authProperties.cookieName());
    assertThat(cookieAPrime).isNotNull();
    assertThat(cookieAPrime.getValue()).isNotEqualTo(cookieA.getValue());

    // A's old cookie no longer works.
    mvc.perform(get("/api/v1/auth/me").cookie(cookieA)).andExpect(status().isUnauthorized());
    // B's cookie no longer works.
    mvc.perform(get("/api/v1/auth/me").cookie(cookieB)).andExpect(status().isUnauthorized());
    // C's cookie no longer works.
    mvc.perform(get("/api/v1/auth/me").cookie(cookieC)).andExpect(status().isUnauthorized());
    // A's new cookie works.
    mvc.perform(get("/api/v1/auth/me").cookie(cookieAPrime)).andExpect(status().isOk());

    // Exactly one event published with sessionsRevokedCount = 2 (B + C).
    List<UserPasswordChangedEvent> events = eventCollector.events();
    assertThat(events).hasSize(1);
    UserPasswordChangedEvent event = events.get(0);
    assertThat(event.userId()).isEqualTo(fixture.userId);
    assertThat(event.sessionsRevokedCount()).isEqualTo(2);

    // Sanity check on session rows: at most one active session for this user (the new one).
    List<Session> active = sessionRepository.findByUserIdAndRevokedAtIsNull(fixture.userId);
    assertThat(active).hasSize(1);
  }

  /**
   * Concurrent password-change race: two simultaneous PUT /password from different sessions of the
   * same user. With {@code @Version} on User the second commit fails with {@code
   * ObjectOptimisticLockingFailureException}, which the global handler maps to 409.
   *
   * <p>TODO: skipped for now — the second tx of the race is hard to make deterministic against a
   * MockMvc test harness because both calls share a thread by default. Re-enable when 01c gets a
   * sibling test that drives the two requests through real concurrent threads, or when an
   * Awaitility + ExecutorService harness is added in a later batch. The handler in {@code
   * GlobalExceptionHandler} is wired up regardless, so the 409 mapping is exercised by future tests
   * without further code change.
   */
  @Test
  void changePassword_concurrentRace_translatesToConflict_TODO() {
    // Intentionally a placeholder. See javadoc above.
  }

  /**
   * Test-scope listener that captures {@link UserPasswordChangedEvent}s published during the IT.
   * Lets us assert the event was emitted exactly once with the right {@code sessionsRevokedCount}.
   */
  @Component
  public static class EventCollector {
    private final List<UserPasswordChangedEvent> captured = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(UserPasswordChangedEvent event) {
      captured.add(event);
    }

    public List<UserPasswordChangedEvent> events() {
      return List.copyOf(captured);
    }

    public void clear() {
      captured.clear();
    }
  }
}
