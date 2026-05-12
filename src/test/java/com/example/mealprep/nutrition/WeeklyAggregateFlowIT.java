package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

/** Full HTTP flow over the weekly intake aggregate endpoint added in 01h. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class WeeklyAggregateFlowIT {

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
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
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
  void getWeeklyAggregate_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/intake/week/2026-05-11/aggregate"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getWeeklyAggregate_returns400_whenWeekStartNotMonday() throws Exception {
    AuthedUser user = registerUser();
    // 2026-05-12 is a Tuesday.
    mvc.perform(get("/api/v1/nutrition/intake/week/2026-05-12/aggregate").cookie(user.cookie()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/invalid-week-start"));
  }

  @Test
  void getWeeklyAggregate_returns200_withSevenZeroDays_whenNoIntake() throws Exception {
    AuthedUser user = registerUser();
    // 2026-05-11 is a Monday.
    mvc.perform(get("/api/v1/nutrition/intake/week/2026-05-11/aggregate").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weekStart").value("2026-05-11"))
        .andExpect(jsonPath("$.weekEnd").value("2026-05-17"))
        .andExpect(jsonPath("$.perDay.length()").value(7))
        .andExpect(jsonPath("$.weeklyTotal.caloriesPlanned").value(0))
        .andExpect(jsonPath("$.weeklyTotal.caloriesActualSoFar").value(0))
        .andExpect(jsonPath("$.floorViolations.length()").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getWeeklyAggregate_includesSnackInActuals() throws Exception {
    AuthedUser user = registerUser();
    // Log a snack on the Wednesday.
    mvc.perform(
            post("/api/v1/nutrition/intake/2026-05-13/snacks")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(NutritionTestData.defaultSnackRequest())))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/nutrition/intake/week/2026-05-11/aggregate").cookie(user.cookie()))
        .andExpect(status().isOk())
        // Wednesday is index 2 (Mon=0).
        .andExpect(jsonPath("$.perDay[2].caloriesActualSoFar").value(180))
        .andExpect(jsonPath("$.weeklyTotal.caloriesActualSoFar").value(180));
  }
}
