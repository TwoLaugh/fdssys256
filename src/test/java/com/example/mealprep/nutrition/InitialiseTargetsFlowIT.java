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

/**
 * Integration test for {@code initialiseTargets} (nutrition-7): the onboarding bootstrap that
 * creates the targets aggregate and DRI-seeds any micronutrient the request omits from the {@code
 * nutrition_dri_defaults} seed table. Runs against real Postgres (the seed migration must have
 * loaded). Also asserts the OpenAPI contract for {@code POST /targets/initialise}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class InitialiseTargetsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "init-targets-" + AuthTestData.shortId();
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
  void initialise_creates201_seedsDriMicrosForUnsuppliedKeys_andPreservesSuppliedOnes()
      throws Exception {
    AuthedUser user = registerUser();

    // The default request supplies iron_mg + vitamin_d_iu only.
    mvc.perform(
            post("/api/v1/nutrition/targets/initialise")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(openApi().isValid(openApiValidator));

    // The supplied iron_mg keeps the request value (18.0), NOT the DRI default (8 or 18 by band).
    java.math.BigDecimal iron =
        jdbcTemplate.queryForObject(
            "SELECT target_value FROM nutrition_micro_target mt"
                + " JOIN nutrition_targets t ON t.id = mt.targets_id"
                + " WHERE t.user_id = ? AND mt.nutrient_key = 'iron_mg'",
            java.math.BigDecimal.class,
            user.userId());
    org.assertj.core.api.Assertions.assertThat(iron)
        .isEqualByComparingTo(java.math.BigDecimal.valueOf(18.0));

    // DRI-seeded micros the request did NOT supply are present (sourced from the seed table).
    Long calciumSeeded =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_micro_target mt"
                + " JOIN nutrition_targets t ON t.id = mt.targets_id"
                + " WHERE t.user_id = ? AND mt.nutrient_key = 'calcium_mg'"
                + " AND mt.source_preference = 'dri_default' AND mt.is_hard_floor = false",
            Long.class,
            user.userId());
    org.assertj.core.api.Assertions.assertThat(calciumSeeded).isEqualTo(1L);

    Long zincSeeded =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_micro_target mt"
                + " JOIN nutrition_targets t ON t.id = mt.targets_id"
                + " WHERE t.user_id = ? AND mt.nutrient_key = 'zinc_mg'",
            Long.class,
            user.userId());
    org.assertj.core.api.Assertions.assertThat(zincSeeded).isEqualTo(1L);
  }

  @Test
  void initialise_secondCall_returns409_targetsAlreadyExist() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            post("/api/v1/nutrition/targets/initialise")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/nutrition/targets/initialise")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isConflict());
  }

  @Test
  void initialisedTargets_areReadableViaGet_withSeededMicros() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            post("/api/v1/nutrition/targets/initialise")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isCreated());

    // Request supplied 2 micros (iron_mg, vitamin_d_iu). The 31-50/female DRI band has 7 micros;
    // iron_mg overlaps (kept from request), so 6 new are seeded → 2 + 6 = 8 total.
    mvc.perform(get("/api/v1/nutrition/targets").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.microTargets.length()").value(8))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
