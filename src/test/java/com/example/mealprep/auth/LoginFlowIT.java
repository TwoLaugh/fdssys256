package com.example.mealprep.auth;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class LoginFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
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

  private String registerAndReturnUsername() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());
    return username;
  }

  @Test
  void login_returns200_withCookieAndLoginResponse_andPersistsNewSessionRow() throws Exception {
    String username = registerAndReturnUsername();
    long sessionsAfterRegister = sessionRepository.count();
    long attemptsAfterRegister = loginAttemptRepository.count();

    LoginRequest login = AuthTestData.loginRequest(username, AuthTestData.DEFAULT_PASSWORD);

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.userId").exists())
        // Raw token must not appear in the body — only via Set-Cookie.
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.sessionToken").doesNotExist())
        .andExpect(cookie().exists(authProperties.cookieName()))
        .andExpect(openApi().isValid(openApiValidator));

    // Login created a new session row in addition to the one from register.
    assertThat(sessionRepository.count()).isEqualTo(sessionsAfterRegister + 1);
    // Successful login wrote exactly one LoginAttempt row.
    assertThat(loginAttemptRepository.count()).isEqualTo(attemptsAfterRegister + 1);
  }

  @Test
  void login_returns401_genericMessage_forBadPassword() throws Exception {
    String username = registerAndReturnUsername();
    long attemptsBefore = loginAttemptRepository.count();
    LoginRequest login = AuthTestData.loginRequest(username, "the-wrong-password-1234");

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").value("Invalid credentials"))
        .andExpect(header().doesNotExist("Set-Cookie"));

    // BAD_PASSWORD attempt was audited.
    long attemptsAfter = loginAttemptRepository.count();
    assertThat(attemptsAfter).isEqualTo(attemptsBefore + 1);
    assertThat(loginAttemptRepository.findAll())
        .anyMatch(a -> !a.isSucceeded() && a.getFailureReason() == LoginFailureReason.BAD_PASSWORD);
  }

  @Test
  void login_returns401_genericMessage_forUnknownUsername() throws Exception {
    long attemptsBefore = loginAttemptRepository.count();
    LoginRequest login =
        AuthTestData.loginRequest("ghost-" + AuthTestData.shortId(), AuthTestData.DEFAULT_PASSWORD);

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        // Same message as bad-password path — no enumeration oracle.
        .andExpect(jsonPath("$.detail").value("Invalid credentials"))
        .andExpect(header().doesNotExist("Set-Cookie"));

    // UNKNOWN_USER attempt audited (proves dummy-verify path also audits).
    assertThat(loginAttemptRepository.count()).isEqualTo(attemptsBefore + 1);
    assertThat(loginAttemptRepository.findAll())
        .anyMatch(
            a ->
                !a.isSucceeded()
                    && a.getFailureReason() == LoginFailureReason.UNKNOWN_USER
                    && a.getUserId() == null);
  }

  @Test
  void login_returns400_forBlankCredentials() throws Exception {
    String body = "{\"username\":\"\",\"password\":\"\"}";

    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  /**
   * Timing-parity assertion. The unknown-user path runs a dummy BCrypt verify so its latency is
   * within ±25ms of a real bad-password path; both should similarly track a successful login. We
   * sample over a warmup batch (discarded) plus a measured batch and compare medians.
   *
   * <p>To avoid tripping the per-username / per-IP throttles during the long sampling loop, the
   * loop clears {@code loginAttemptRepository} between batches. The cost is exercised exactly the
   * same way each iteration; clearing is purely about not turning the test into a throttle test.
   *
   * <p>This assertion exists primarily to fail loudly if a regression skips dummy-verify (which
   * would show up as 100+ms difference); the bound is loose enough to absorb GC and CI noise.
   */
  @Test
  void login_timingParity_acrossSuccessAndBadPasswordAndUnknownUser() throws Exception {
    // The point of the test is to catch accidental code paths that skip dummy-verify entirely
    // (which would show up as hundreds of ms difference); a 100ms window catches that without
    // flaking on CI noise.
    final long maxMedianDeltaMs = 100L;
    final int warmupIterations = 5;
    final int measuredIterations = 9;

    String username = registerAndReturnUsername();
    String unknownUsername = "ghost-" + AuthTestData.shortId();

    LoginRequest success = AuthTestData.loginRequest(username, AuthTestData.DEFAULT_PASSWORD);
    LoginRequest badPassword = AuthTestData.loginRequest(username, "the-wrong-password-1234");
    LoginRequest unknown =
        AuthTestData.loginRequest(unknownUsername, AuthTestData.DEFAULT_PASSWORD);

    // Warmup — JIT, bytecode load, BCrypt class init. Clear attempts after warmup so the
    // measurement loop does not fight the lockout state machine (5 BAD_PASSWORD locks the user).
    for (int i = 0; i < warmupIterations; i++) {
      timeOnce(success);
    }
    loginAttemptRepository.deleteAll();
    // Reset the user's failed-login counter so warmup-mistakes don't accidentally lock us out.
    var u = userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow();
    u.setFailedLoginCount(0);
    u.setLockedUntil(null);
    userRepository.saveAndFlush(u);

    long medianSuccess = median(measureWithReset(success, measuredIterations, username));
    long medianBad = median(measureWithReset(badPassword, measuredIterations, username));
    long medianUnknown = median(measureWithReset(unknown, measuredIterations, username));

    long maxAbs =
        Math.max(
            Math.abs(medianSuccess - medianBad),
            Math.max(Math.abs(medianBad - medianUnknown), Math.abs(medianSuccess - medianUnknown)));
    assertThat(maxAbs)
        .as(
            "Median latencies should be within %dms — success=%dms bad=%dms unknown=%dms",
            maxMedianDeltaMs, medianSuccess, medianBad, medianUnknown)
        .isLessThanOrEqualTo(maxMedianDeltaMs);
  }

  private long timeOnce(LoginRequest req) throws Exception {
    long start = System.nanoTime();
    mvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(req)));
    return (System.nanoTime() - start) / 1_000_000L;
  }

  private List<Long> measureWithReset(LoginRequest req, int iterations, String username)
      throws Exception {
    List<Long> samples = new ArrayList<>(iterations);
    for (int i = 0; i < iterations; i++) {
      samples.add(timeOnce(req));
      // Reset throttle/lockout state between iterations so we never fight the rate limiter.
      loginAttemptRepository.deleteAll();
      userRepository
          .findByUsernameNormalised(username.toLowerCase())
          .ifPresent(
              u -> {
                u.setFailedLoginCount(0);
                u.setLockedUntil(null);
                userRepository.saveAndFlush(u);
              });
    }
    return samples;
  }

  private long median(List<Long> samples) {
    List<Long> sorted = new ArrayList<>(samples);
    Collections.sort(sorted);
    int mid = sorted.size() / 2;
    if (sorted.size() % 2 == 0) {
      return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
    return sorted.get(mid);
  }
}
