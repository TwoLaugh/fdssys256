package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.nullValue;
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
import com.example.mealprep.nutrition.api.dto.EatingWindowDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
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

  @Test
  void getWeeklyAggregate_floorViolations_datedForDailyFloors_undatedForWeeklyAverage()
      throws Exception {
    AuthedUser user = registerUser();
    // protein: daily_floor enforcement with a 100g floor -> dated entry for the seeded day.
    // carbs: weekly_average enforcement with a 100g floor -> single undated entry (7-day floor
    // 700g vs weekly total).
    UpdateTargetsRequest targets =
        new UpdateTargetsRequest(
            Goal.MAINTAIN,
            NutritionTestData.defaultCalories(),
            new MacroTargetDto(
                BigDecimal.valueOf(120.0),
                BigDecimal.valueOf(100.0),
                "daily_floor",
                EnforcementDirection.LOWER_FLOOR,
                true),
            new MacroTargetDto(
                BigDecimal.valueOf(250.0),
                BigDecimal.valueOf(100.0),
                "weekly_average",
                EnforcementDirection.BOTH_BOUNDED,
                true),
            new MacroTargetDto(
                BigDecimal.valueOf(70.0),
                null,
                "weekly_average",
                EnforcementDirection.BOTH_BOUNDED,
                true),
            new MacroTargetDto(
                BigDecimal.valueOf(30.0),
                null,
                "daily_floor",
                EnforcementDirection.LOWER_FLOOR,
                true),
            new MacroTargetDto(
                BigDecimal.valueOf(20.0), null, null, EnforcementDirection.UPPER_LIMIT, false),
            "Floor enforcement matrix",
            NutritionTestData.defaultPerMealList(),
            NutritionTestData.defaultMicros(),
            new EatingWindowDto(false, null, null, null),
            NutritionTestData.defaultActivities(),
            0L);
    mvc.perform(
            post("/api/v1/nutrition/targets/initialise")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(targets)))
        .andExpect(status().isCreated());

    // Seed the Wednesday: snack with 7g protein (< 100 floor) carrying saturated fat micros.
    ObjectNode micros = objectMapper.createObjectNode();
    micros.put("saturated_fat_g", 2.0);
    mvc.perform(
            post("/api/v1/nutrition/intake/2026-05-13/snacks")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        NutritionTestData.snackRequestWithMicros(micros))))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/nutrition/intake/week/2026-05-11/aggregate").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.floorViolations.length()").value(2))
        // Daily-enforcement protein floor: dated entry matching the seeded violation day; the
        // six untracked days are absent data, not violations.
        .andExpect(jsonPath("$.floorViolations[0].macroOrMicro").value("protein"))
        .andExpect(jsonPath("$.floorViolations[0].date").value("2026-05-13"))
        .andExpect(jsonPath("$.floorViolations[0].floor").value(100.0))
        .andExpect(jsonPath("$.floorViolations[0].actual").value(7.0))
        // Weekly-average carbs floor: undated entry with the 7-day-summed floor.
        .andExpect(jsonPath("$.floorViolations[1].macroOrMicro").value("carbs"))
        .andExpect(jsonPath("$.floorViolations[1].date").value(nullValue()))
        .andExpect(jsonPath("$.floorViolations[1].floor").value(700.0))
        .andExpect(jsonPath("$.floorViolations[1].actual").value(6.0))
        // satFat aggregate rides perDay + weeklyTotal for free (sibling ticket).
        .andExpect(jsonPath("$.perDay[2].satFat.actualSoFarG").value(2.0))
        .andExpect(jsonPath("$.weeklyTotal.satFat.actualSoFarG").value(2.0))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
