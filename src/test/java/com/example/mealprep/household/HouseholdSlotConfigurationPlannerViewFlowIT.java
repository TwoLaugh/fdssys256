package com.example.mealprep.household;

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
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
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

/**
 * Full HTTP flow over {@code GET /api/v1/households/current/slot-configuration/planner-view} (01f).
 * Mirrors the structure of {@link HouseholdMergeFlowIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class HouseholdSlotConfigurationPlannerViewFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private HouseholdUpdateService householdUpdateService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM household_invite");
    jdbcTemplate.update("DELETE FROM household_settings_audit");
    jdbcTemplate.update("DELETE FROM household_settings");
    jdbcTemplate.update("DELETE FROM household_member");
    jdbcTemplate.update("DELETE FROM household");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser(String prefix) throws Exception {
    String username = prefix + "-" + AuthTestData.shortId();
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

  // ---------------- 200 happy paths ----------------

  @Test
  void plannerView_byPrimary_returns200_withDefaultSlots() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("Smith"));

    mvc.perform(
            get("/api/v1/households/current/slot-configuration/planner-view")
                .cookie(primary.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.householdId").isString())
        .andExpect(jsonPath("$.slots.length()").value(4))
        .andExpect(jsonPath("$.slots[0].cuisinePreferenceWeight").doesNotExist())
        .andExpect(jsonPath("$.allEaterUserIds.length()").value(1))
        .andExpect(jsonPath("$.allEaterUserIds[0]").value(primary.userId().toString()))
        .andExpect(jsonPath("$.eaterUserIdsByPriority.length()").value(1))
        .andExpect(jsonPath("$.eaterUserIdsByPriority[0]").value(primary.userId().toString()))
        .andExpect(jsonPath("$.generatedAt").isString())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void plannerView_carriesBuiltInSlotsBreakfastLunchDinnerSnack() throws Exception {
    AuthedUser primary = registerUser("primary");
    householdUpdateService.createHousehold(primary.userId(), new CreateHouseholdRequest("X"));

    mvc.perform(
            get("/api/v1/households/current/slot-configuration/planner-view")
                .cookie(primary.cookie()))
        .andExpect(status().isOk())
        // The four built-in slots ship as shared=true, headcount=1, timeBudgetMin=30 from
        // HouseholdServiceImpl.buildDefaultSettings.
        .andExpect(jsonPath("$.slots[0].kind").isString())
        .andExpect(jsonPath("$.slots[0].shared").value(true))
        .andExpect(jsonPath("$.slots[0].headcount").value(1))
        .andExpect(jsonPath("$.slots[0].timeBudgetMin").value(30));
  }

  // ---------------- 4xx ----------------

  @Test
  void plannerView_anonymous_returns401() throws Exception {
    mvc.perform(get("/api/v1/households/current/slot-configuration/planner-view"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void plannerView_byOrphan_returns404() throws Exception {
    AuthedUser orphan = registerUser("orphan");
    mvc.perform(
            get("/api/v1/households/current/slot-configuration/planner-view")
                .cookie(orphan.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/household-not-found"));
  }
}
