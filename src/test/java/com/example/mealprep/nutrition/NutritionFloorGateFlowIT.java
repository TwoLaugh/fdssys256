package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/**
 * HTTP flow over {@code POST /api/v1/nutrition/floor-gate/evaluate}. Covers happy path with no
 * targets (200 passed=true), passed=false outcome (still 200), anonymous (401), and the two
 * service-layer validation paths (400 endDate < startDate; 400 day outside range).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class NutritionFloorGateFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private NutritionTargetsRepository targetsRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_activity_adjustment");
    jdbcTemplate.update("DELETE FROM nutrition_eating_window");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_per_meal_distribution");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "fgate-" + AuthTestData.shortId();
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

  /** Persist a targets row with a non-null protein floor so the gate has something to check. */
  private NutritionTargets seedTargetsWithProteinFloor(UUID userId, BigDecimal proteinFloorG) {
    NutritionTargets t =
        NutritionTargets.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .goal(Goal.MAINTAIN)
            .dailyCalorieTarget(2000)
            .calorieToleranceUnder(100)
            .calorieToleranceOver(150)
            .calorieEnforcement("weekly_average")
            .calorieDirection(EnforcementDirection.BOTH_BOUNDED)
            .proteinTargetG(BigDecimal.valueOf(120.0))
            .proteinFloorG(proteinFloorG)
            .proteinEnforcement("daily_floor")
            .proteinDirection(EnforcementDirection.LOWER_FLOOR)
            .carbsTargetG(BigDecimal.valueOf(250.0))
            .carbsFloorG(null)
            .carbsEnforcement("weekly_average")
            .carbsDirection(EnforcementDirection.BOTH_BOUNDED)
            .fatTargetG(BigDecimal.valueOf(70.0))
            .fatFloorG(null)
            .fatEnforcement("weekly_average")
            .fatDirection(EnforcementDirection.BOTH_BOUNDED)
            .fibreTargetG(BigDecimal.valueOf(30.0))
            .fibreFloorG(null)
            .fibreEnforcement("daily_floor")
            .fibreDirection(EnforcementDirection.LOWER_FLOOR)
            .satFatTargetG(BigDecimal.valueOf(20.0))
            .satFatDirection(EnforcementDirection.UPPER_LIMIT)
            .notes(null)
            .userOverriddenDirections(new ArrayList<>())
            .perMealDistribution(new ArrayList<>())
            .microTargets(new ArrayList<>())
            .activityAdjustments(new ArrayList<>())
            .eatingWindow(null)
            .build();
    return targetsRepository.saveAndFlush(t);
  }

  @Test
  void evaluate_returns401_whenAnonymous() throws Exception {
    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 9))));

    mvc.perform(
            post("/api/v1/nutrition/floor-gate/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rollup)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void evaluate_returns200_passedTrue_whenNoTargetsConfigured() throws Exception {
    AuthedUser user = registerUser();
    CandidatePlanRollupDto rollup =
        NutritionTestData.planRollup(
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 9))));

    mvc.perform(
            post("/api/v1/nutrition/floor-gate/evaluate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rollup)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true))
        .andExpect(jsonPath("$.violations").isArray())
        .andExpect(jsonPath("$.violations.length()").value(0))
        .andExpect(jsonPath("$.summary").exists())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void evaluate_returns200_passedFalse_whenProteinFloorBreached() throws Exception {
    AuthedUser user = registerUser();
    seedTargetsWithProteinFloor(user.userId(), BigDecimal.valueOf(100.0));
    CandidateDailyRollupDto under =
        NutritionTestData.dailyRollup(
            LocalDate.of(2026, 5, 9),
            BigDecimal.valueOf(50.0),
            BigDecimal.valueOf(260.0),
            BigDecimal.valueOf(80.0),
            BigDecimal.valueOf(35.0));
    CandidatePlanRollupDto rollup = NutritionTestData.planRollup(List.of(under));

    mvc.perform(
            post("/api/v1/nutrition/floor-gate/evaluate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rollup)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(false))
        .andExpect(jsonPath("$.violations.length()").value(1))
        .andExpect(jsonPath("$.violations[0].macroOrMicro").value("protein"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void evaluate_returns400_whenEndDateBeforeStartDate() throws Exception {
    AuthedUser user = registerUser();
    CandidatePlanRollupDto rollup =
        new CandidatePlanRollupDto(
            LocalDate.of(2026, 5, 10),
            LocalDate.of(2026, 5, 9),
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 10))));

    mvc.perform(
            post("/api/v1/nutrition/floor-gate/evaluate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rollup)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value(org.hamcrest.Matchers.containsString("invalid-plan-rollup")));
  }

  @Test
  void evaluate_returns400_whenDayDateOutsideRange() throws Exception {
    AuthedUser user = registerUser();
    CandidatePlanRollupDto rollup =
        new CandidatePlanRollupDto(
            LocalDate.of(2026, 5, 9),
            LocalDate.of(2026, 5, 10),
            List.of(NutritionTestData.dailyRollup(LocalDate.of(2026, 5, 12))));

    mvc.perform(
            post("/api/v1/nutrition/floor-gate/evaluate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rollup)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value(org.hamcrest.Matchers.containsString("invalid-plan-rollup")));
  }

  @Test
  void evaluate_returns400_whenPerDayEmpty() throws Exception {
    AuthedUser user = registerUser();
    String body = "{\"startDate\":\"2026-05-09\",\"endDate\":\"2026-05-09\",\"perDay\":[]}";

    mvc.perform(
            post("/api/v1/nutrition/floor-gate/evaluate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }
}
