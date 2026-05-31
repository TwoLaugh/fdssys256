package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
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

/** Full HTTP + OpenAPI-contract flow over the daily intake aggregate endpoint (nutrition-5). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class DailyAggregateFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_intake_audit");
    jdbcTemplate.update("DELETE FROM nutrition_intake_snack");
    jdbcTemplate.update("DELETE FROM nutrition_intake_slot");
    jdbcTemplate.update("DELETE FROM nutrition_intake_day");
    jdbcTemplate.update("DELETE FROM nutrition_divergence_state");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "daily-agg-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  @Test
  void getDailyAggregate_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/intake/2026-05-13/aggregate"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getDailyAggregate_returns200_zeroValued_whenNoIntakeAndNoTargets() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/nutrition/intake/2026-05-13/aggregate").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.caloriesPlanned").value(0))
        .andExpect(jsonPath("$.caloriesActualSoFar").value(0))
        // No targets → remaining falls back to max(0, planned-actual) = 0.
        .andExpect(jsonPath("$.caloriesRemaining").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getDailyAggregate_remainingIsTargetBased_afterTargetsInitialisedAndSnackLogged()
      throws Exception {
    AuthedUser user = registerUser();
    // Initialise targets (2000 kcal daily target via the default request).
    mvc.perform(
            post("/api/v1/nutrition/targets/initialise")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isCreated());

    // Log a 180-kcal snack on the day.
    mvc.perform(
            post("/api/v1/nutrition/intake/2026-05-13/snacks")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(NutritionTestData.defaultSnackRequest())))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/nutrition/intake/2026-05-13/aggregate").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.caloriesActualSoFar").value(180))
        // target-based: 2000 - 180 = 1820 (NOT planned-based, which would be 0 for a snack-only
        // day).
        .andExpect(jsonPath("$.caloriesRemaining").value(1820))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
