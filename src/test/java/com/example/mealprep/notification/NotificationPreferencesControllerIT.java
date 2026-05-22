package com.example.mealprep.notification;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP-flow IT over {@code NotificationPreferencesController}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class NotificationPreferencesControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM notification_delivery_log");
    jdbcTemplate.update("DELETE FROM notifications");
    jdbcTemplate.update("DELETE FROM notification_preferences");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private Cookie registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("pref-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return result.getResponse().getCookie(authProperties.cookieName());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
      String url) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url);
  }

  private ObjectNode validUpdateBody() {
    ObjectNode body = objectMapper.createObjectNode();
    ObjectNode enabled = body.putObject("enabledKinds");
    enabled.put("PROVISION_ITEM_NEAR_EXPIRY", true);
    enabled.put("PROVISION_ITEM_SPOILED", true);
    enabled.put("PROVISION_DEFROST_REMINDER", true);
    enabled.put("NUTRITION_INTAKE_DIVERGED", true);
    enabled.put("HEALTH_DIRECTIVE_RECEIVED", true);
    enabled.put("PLANNER_PREP_REMINDER", true);
    enabled.put("PLANNER_REOPT_SUGGESTED", true);
    enabled.put("PLANNER_PLAN_GENERATED", false);
    body.put("quietHoursEnabled", true);
    body.put("quietHoursStart", "22:00:00");
    body.put("quietHoursEnd", "06:00:00");
    body.put("timezone", "Europe/London");
    body.put("debounceWindowMinutes", 30);
    body.put("expectedVersion", 0);
    return body;
  }

  @Test
  void get_autoSeedsDefaults() throws Exception {
    Cookie cookie = registerUser();
    mvc.perform(get("/api/v1/notifications/preferences").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabledKinds.PLANNER_PLAN_GENERATED").value(false))
        .andExpect(jsonPath("$.timezone").value("Europe/London"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void put_happyPath_updates() throws Exception {
    Cookie cookie = registerUser();
    mvc.perform(get("/api/v1/notifications/preferences").cookie(cookie)).andExpect(status().isOk());

    mvc.perform(
            put("/api/v1/notifications/preferences")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validUpdateBody())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quietHoursEnabled").value(true))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void put_invalidQuietHours_400() throws Exception {
    Cookie cookie = registerUser();
    mvc.perform(get("/api/v1/notifications/preferences").cookie(cookie)).andExpect(status().isOk());

    ObjectNode body = validUpdateBody();
    body.put("quietHoursEnabled", true);
    body.putNull("quietHoursStart");
    body.putNull("quietHoursEnd");

    mvc.perform(
            put("/api/v1/notifications/preferences")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void put_staleVersion_409() throws Exception {
    Cookie cookie = registerUser();
    mvc.perform(get("/api/v1/notifications/preferences").cookie(cookie)).andExpect(status().isOk());

    ObjectNode body = validUpdateBody();
    body.put("expectedVersion", 99);

    mvc.perform(
            put("/api/v1/notifications/preferences")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }
}
