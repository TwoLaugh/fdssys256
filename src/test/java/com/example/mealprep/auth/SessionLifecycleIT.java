package com.example.mealprep.auth;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class SessionLifecycleIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record RegisteredFixture(String username, UUID userId, Cookie cookie) {}

  private RegisteredFixture register() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    UUID userId =
        userRepository.findByUsernameNormalised(username.toLowerCase()).orElseThrow().getId();
    return new RegisteredFixture(username, userId, cookie);
  }

  @Test
  void getMe_returns200_withCookie_andReturnsRegisteredUser() throws Exception {
    RegisteredFixture fixture = register();

    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(fixture.username))
        .andExpect(jsonPath("$.userId").value(fixture.userId.toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getMe_returns401_withoutCookie() throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getMe_returns401_withMangledCookie() throws Exception {
    Cookie tampered = new Cookie(authProperties.cookieName(), "this-is-not-a-valid-token");

    mvc.perform(get("/api/v1/auth/me").cookie(tampered))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  @Transactional
  void getMe_returns401_whenSessionForceExpired() throws Exception {
    RegisteredFixture fixture = register();
    Session session = sessionRepository.findAll().get(0);
    session.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
    sessionRepository.saveAndFlush(session);

    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  @Transactional
  void getMe_returns401_whenSessionForceRevoked() throws Exception {
    RegisteredFixture fixture = register();
    Session session = sessionRepository.findAll().get(0);
    session.setRevokedAt(Instant.now());
    sessionRepository.saveAndFlush(session);

    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  @Transactional
  void getMe_returns401_whenUserSoftDeleted() throws Exception {
    RegisteredFixture fixture = register();
    User user = userRepository.findById(fixture.userId).orElseThrow();
    user.setDeletedAt(Instant.now());
    userRepository.saveAndFlush(user);

    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getMe_softDeletedUser_revokesTheStillActiveSession() throws Exception {
    // auth-6: when the filter observes a still-active session of a soft-deleted user, it leaves the
    // context anonymous (401) AND revokes the session in a separate tx so the stale credential
    // can't be re-presented. Non-transactional so the SessionRevoker's REQUIRES_NEW commit is
    // observable; cleaned up explicitly afterwards.
    RegisteredFixture fixture = register();
    try {
      User user = userRepository.findById(fixture.userId).orElseThrow();
      user.setDeletedAt(Instant.now());
      userRepository.saveAndFlush(user);

      UUID sessionId = sessionRepository.findAll().get(0).getId();
      assertThat(sessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNull();

      mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
          .andExpect(status().isUnauthorized())
          .andExpect(openApi().isValid(openApiValidator));

      // The filter's best-effort revoke committed: the session row now carries revokedAt.
      assertThat(sessionRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
    } finally {
      sessionRepository.deleteAll();
      userRepository.deleteAll();
    }
  }

  @Test
  void logout_returns204_andClearsCookie_andRevokesSession() throws Exception {
    RegisteredFixture fixture = register();

    mvc.perform(post("/api/v1/auth/logout").cookie(fixture.cookie))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge(authProperties.cookieName(), 0))
        .andExpect(openApi().isValid(openApiValidator));

    Session session = sessionRepository.findAll().get(0);
    assertThat(session.getRevokedAt()).isNotNull();

    // Subsequent /me with the same cookie should now fail.
    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isUnauthorized())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void logout_isIdempotent_whenCalledWithoutValidSession() throws Exception {
    Cookie tampered = new Cookie(authProperties.cookieName(), "ghost");

    mvc.perform(post("/api/v1/auth/logout").cookie(tampered))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge(authProperties.cookieName(), 0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void lastSeenAt_doesNotChange_betweenAuthenticatedRequests() throws Exception {
    RegisteredFixture fixture = register();
    Instant beforeFirstRequest = sessionRepository.findAll().get(0).getLastSeenAt();

    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isOk())
        .andExpect(openApi().isValid(openApiValidator));
    mvc.perform(get("/api/v1/auth/me").cookie(fixture.cookie))
        .andExpect(status().isOk())
        .andExpect(openApi().isValid(openApiValidator));

    Instant afterRequests = sessionRepository.findAll().get(0).getLastSeenAt();
    assertThat(afterRequests).isEqualTo(beforeFirstRequest);
  }
}
