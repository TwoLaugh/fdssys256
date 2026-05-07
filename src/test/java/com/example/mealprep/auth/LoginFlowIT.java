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
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
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
  }

  @Test
  void login_returns401_genericMessage_forBadPassword() throws Exception {
    String username = registerAndReturnUsername();
    LoginRequest login = AuthTestData.loginRequest(username, "the-wrong-password-1234");

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(login)))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").value("Invalid credentials"))
        .andExpect(header().doesNotExist("Set-Cookie"));
  }

  @Test
  void login_returns401_genericMessage_forUnknownUsername() throws Exception {
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
  }

  @Test
  void login_returns400_forBlankCredentials() throws Exception {
    String body = "{\"username\":\"\",\"password\":\"\"}";

    mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }
}
