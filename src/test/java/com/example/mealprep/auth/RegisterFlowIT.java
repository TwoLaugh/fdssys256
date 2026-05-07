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
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Full HTTP flow for registration. Uses Testcontainers Postgres so the unique-index 409 path is
 * exercised against a real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class RegisterFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  void register_returns201_withCookieAndUserDto_andPersistsRow() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("alice-" + AuthTestData.shortId());

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.userId").exists())
        .andExpect(jsonPath("$.username").value(body.username()))
        .andExpect(jsonPath("$.createdAt").exists())
        // Token never appears in the body.
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(cookie().exists(authProperties.cookieName()))
        .andExpect(cookie().httpOnly(authProperties.cookieName(), true))
        .andExpect(cookie().path(authProperties.cookieName(), "/"))
        .andExpect(
            cookie()
                .maxAge(authProperties.cookieName(), (int) authProperties.sessionTtl().toSeconds()))
        // SameSite + Secure live in the raw header on the ResponseCookie path.
        .andExpect(header().string("Set-Cookie", Matchers.containsString("SameSite=Lax")))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(userRepository.findByUsernameNormalised(body.username().toLowerCase())).isPresent();
    long sessionCount = sessionRepository.count();
    assertThat(sessionCount).isEqualTo(1L);
  }

  @Test
  void register_returns409_onDuplicateUsername() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void register_returns400_onWeakPassword() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("alice-" + AuthTestData.shortId(), "short");

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
  }

  @Test
  void register_returns400_onInvalidUsername() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("a!", AuthTestData.DEFAULT_PASSWORD);

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists());
  }

  @Test
  void register_normalisesUsernameCaseForUniquenessCheck() throws Exception {
    String suffix = AuthTestData.shortId();
    RegisterRequest first = AuthTestData.registerRequest("Alice-" + suffix);
    RegisterRequest second = AuthTestData.registerRequest("alice-" + suffix);

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
        .andExpect(status().isCreated());

    // Same lower-case form already exists — the second registration with different casing must
    // collide on the username_normalised unique index.
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(second)))
        .andExpect(status().isConflict());
  }
}
