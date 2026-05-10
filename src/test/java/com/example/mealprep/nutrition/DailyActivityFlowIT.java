package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.UpsertDailyActivityRequest;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
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

/** HTTP flow over the daily-activity log: PUT upsert + GET range. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class DailyActivityFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_daily_activity_log");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "carol-" + AuthTestData.shortId();
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
  void put_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            put("/api/v1/nutrition/targets/activity/2026-05-09")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpsertDailyActivityRequest(ActivityLevel.REST_DAY, null))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void put_firstCall_creates_secondCall_updates() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            put("/api/v1/nutrition/targets/activity/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpsertDailyActivityRequest(ActivityLevel.REST_DAY, "lazy day"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activityLevel").value("REST_DAY"))
        .andExpect(jsonPath("$.notes").value("lazy day"))
        .andExpect(openApi().isValid(openApiValidator));

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_daily_activity_log WHERE user_id = ?",
            Long.class,
            user.userId());
    assertThat(count).isEqualTo(1L);

    // Second PUT — same date, different level: row updated, no second row.
    mvc.perform(
            put("/api/v1/nutrition/targets/activity/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpsertDailyActivityRequest(ActivityLevel.TRAINING_DAY, "leg day"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activityLevel").value("TRAINING_DAY"));

    String level =
        jdbcTemplate.queryForObject(
            "SELECT activity_level FROM nutrition_daily_activity_log WHERE user_id = ?",
            String.class,
            user.userId());
    assertThat(level).isEqualTo("TRAINING_DAY");
  }

  @Test
  void getRange_returns400_whenInvalidRange() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/nutrition/targets/activity")
                .cookie(user.cookie())
                .param("from", "2026-05-10")
                .param("to", "2026-05-09"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getRange_returns200_withRows() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/nutrition/targets/activity/2026-05-09")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new UpsertDailyActivityRequest(ActivityLevel.LIGHT_ACTIVITY, null))))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/nutrition/targets/activity")
                .cookie(user.cookie())
                .param("from", "2026-05-01")
                .param("to", "2026-05-31"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].activityLevel").value("LIGHT_ACTIVITY"))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
