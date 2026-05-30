package com.example.mealprep.auth;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.LoginRequest;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.LoginFailureReason;
import com.example.mealprep.auth.domain.repository.LoginAttemptRepository;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end IT for the 01b defensive layer. Exercises the throttle, lockout, and audit-row
 * behaviours against a real Postgres via Testcontainers. Each test cleans up between runs because
 * the rolling window is based on wall-clock time on the row's {@code attempted_at} column.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class ThrottlingAndLockoutIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private LoginAttemptRepository loginAttemptRepository;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    loginAttemptRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private String register() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(openApi().isValid(openApiValidator));
    return username;
  }

  private void postLogin(String username, String password, int expectedStatus) throws Exception {
    LoginRequest body = AuthTestData.loginRequest(username, password);
    // 200/401/423 are all documented for POST /api/v1/auth/login (problem+json for 4xx).
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().is(expectedStatus))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- per-username throttle ----------------

  @Test
  void perUsername_eleventhFailedAttemptReturns429_withRetryAfter() throws Exception {
    // Use a username that is NOT registered so we hit UNKNOWN_USER without ever touching the
    // lockout state machine. Throttle still counts UNKNOWN_USER toward its 10-failure threshold.
    String ghost = "ghost-" + AuthTestData.shortId();

    for (int i = 0; i < authProperties.throttle().usernameMaxFailures(); i++) {
      postLogin(ghost, "any-password-1234", 401);
    }

    // 11th attempt — throttle has been crossed, expect 429 with Retry-After.
    LoginRequest body = AuthTestData.loginRequest(ghost, "any-password-1234");
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("Retry-After", Matchers.matchesPattern("\\d+")))
        .andExpect(openApi().isValid(openApiValidator));

    // Throttle response is itself audited as THROTTLED.
    assertThat(loginAttemptRepository.findAll())
        .anyMatch(a -> a.getFailureReason() == LoginFailureReason.THROTTLED);
  }

  // ---------------- per-IP throttle ----------------

  @Test
  void perIp_thirtyFirstFailedAttemptReturns429_evenWhenUsernamesRotate() throws Exception {
    // Spray UNKNOWN_USER across many distinct usernames from the same IP. Per-username throttle
    // never trips (each user only sees ≤1 failure); per-IP must still cap at 30.
    int ipMax = authProperties.throttle().ipMaxFailures();
    for (int i = 0; i < ipMax; i++) {
      String randomGhost = "ghost-" + AuthTestData.shortId() + "-" + i;
      postLogin(randomGhost, "any-password-1234", 401);
    }

    String oneMore = "ghost-" + AuthTestData.shortId() + "-final";
    LoginRequest body = AuthTestData.loginRequest(oneMore, "any-password-1234");
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- lockout ----------------

  @Test
  void fiveConsecutiveBadPasswords_locksAccount_andSixthAttemptReturns423() throws Exception {
    String username = register();

    int threshold = authProperties.lockout().threshold();
    for (int i = 0; i < threshold; i++) {
      postLogin(username, "the-wrong-password-12345", 401);
    }

    // 6th attempt: account is locked → 423 with Retry-After.
    LoginRequest body = AuthTestData.loginRequest(username, "the-wrong-password-12345");
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isLocked())
        .andExpect(header().exists("Retry-After"))
        .andExpect(openApi().isValid(openApiValidator));

    // failedLoginCount + lockedUntil persisted on the user row.
    var user = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(user.getFailedLoginCount()).isGreaterThanOrEqualTo(threshold);
    assertThat(user.getLockedUntil()).isNotNull();

    // ACCOUNT_LOCKED audit row was written.
    assertThat(loginAttemptRepository.findAll())
        .anyMatch(a -> a.getFailureReason() == LoginFailureReason.ACCOUNT_LOCKED);
  }

  @Test
  void unknownUserAttemptsDoNotLockOutRealUser() throws Exception {
    // Critical security invariant: if Mallory cycles random usernames, an unknown-user does not
    // exist as a row to lock. We verify the absence of any locked user after 10 unknown-user
    // attempts (more than the lockout threshold of 5) — none of them touched any user row.
    String ghost = "ghost-" + AuthTestData.shortId();
    int beyondLockoutThreshold = authProperties.lockout().threshold() + 5;
    for (int i = 0; i < beyondLockoutThreshold; i++) {
      // Throttle bites at 10, so cap at threshold + 5 = 10 to stay under it.
      postLogin(ghost, "any-password-1234", 401);
    }

    // No User row was ever created or modified by these attempts.
    assertThat(userRepository.findByUsernameNormalised(ghost)).isEmpty();
    // No user has lockedUntil set (no real users in this test).
    assertThat(userRepository.findAll()).noneMatch(u -> u.getLockedUntil() != null);
  }

  @Test
  void successfulLoginResetsFailedLoginCount_whenInterleaved() throws Exception {
    String username = register();

    // Three bad attempts — under threshold but builds up the counter.
    for (int i = 0; i < 3; i++) {
      postLogin(username, "the-wrong-password-12345", 401);
    }
    var user = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(user.getFailedLoginCount()).isEqualTo(3);

    // One success — counter must reset to 0 in the same statement that touches lastLoginAt.
    postLogin(username, AuthTestData.DEFAULT_PASSWORD, 200);
    user = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(user.getFailedLoginCount()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.getLastLoginAt()).isNotNull();

    // Subsequent failures start counting from zero again — proving the reset, not just a
    // cosmetic refresh.
    for (int i = 0; i < 4; i++) {
      postLogin(username, "the-wrong-password-12345", 401);
    }
    user = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(user.getFailedLoginCount()).isEqualTo(4); // not 7
    assertThat(user.getLockedUntil()).isNull(); // still under threshold
  }

  @Test
  void
      afterLockoutWindowExpires_singleBadPasswordDoesNotInstantlyRelock_andCorrectPasswordSucceeds()
          throws Exception {
    // auth-1 regression: lock the account, then simulate the lockout window having elapsed by
    // back-dating lockedUntil. The very next failed attempt must NOT instantly re-lock (the stale
    // counter is cleared before the attempt is evaluated), and a correct password then succeeds.
    String username = register();
    int threshold = authProperties.lockout().threshold();
    for (int i = 0; i < threshold; i++) {
      postLogin(username, "the-wrong-password-12345", 401);
    }
    var locked = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(locked.getLockedUntil()).isNotNull();
    assertThat(locked.getFailedLoginCount()).isGreaterThanOrEqualTo(threshold);

    // Window has passed: move lockedUntil into the past. (Login attempts above keep us under the
    // 10-failure username throttle, so the next single failure is evaluated, not throttled.)
    locked.setLockedUntil(Instant.now().minus(Duration.ofMinutes(1)));
    userRepository.saveAndFlush(locked);

    // One more wrong password — must be a normal 401, NOT a 423 re-lock.
    postLogin(username, "the-wrong-password-12345", 401);
    var afterExpiry = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(afterExpiry.getLockedUntil()).isNull();
    // Counter restarted from zero before this attempt → exactly one failure now.
    assertThat(afterExpiry.getFailedLoginCount()).isEqualTo(1);

    // And the correct password logs in (would be impossible if the account had re-locked).
    postLogin(username, AuthTestData.DEFAULT_PASSWORD, 200);
    var afterSuccess =
        userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(afterSuccess.getFailedLoginCount()).isZero();
    assertThat(afterSuccess.getLockedUntil()).isNull();
    assertThat(afterSuccess.getLastLoginAt()).isNotNull();
  }

  @Test
  void lockoutCounterIncrementsOnBadPasswordOnly_notOnAccountLockedFollowups() throws Exception {
    // Confirm that, once a user is locked, repeated calls do not keep incrementing
    // failedLoginCount past the threshold — the path short-circuits at the lockout check.
    String username = register();
    int threshold = authProperties.lockout().threshold();
    for (int i = 0; i < threshold; i++) {
      postLogin(username, "the-wrong-password-12345", 401);
    }
    var user = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    int countAfterLockout = user.getFailedLoginCount();

    // Two more calls during lockout window — both 423.
    postLogin(username, "the-wrong-password-12345", 423);
    postLogin(username, AuthTestData.DEFAULT_PASSWORD, 423);

    user = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    assertThat(user.getFailedLoginCount()).isEqualTo(countAfterLockout);
  }
}
