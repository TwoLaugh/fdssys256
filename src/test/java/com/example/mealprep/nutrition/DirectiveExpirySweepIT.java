package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.InboundHealthDirectiveRequest;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
 * Integration test for the directive auto-expiry sweep (nutrition-3 / LLD Flow 8 line 1022). Runs
 * against the full context + real Postgres so the real {@code PreferenceDirectiveApplyTarget} wins
 * and {@code removeTemporaryConstraint} actually reverses the temporary hard constraint.
 *
 * <p>Drives {@link NutritionUpdateService#sweepExpiredDirectives()} directly (the
 * {@code @Scheduled} cron only fires at 04:00) and asserts: an ACCEPTED directive past its {@code
 * auto_expires_at} transitions to EXPIRED and its temporary preference effect is reverted; a
 * directive whose expiry is in the future is untouched; the sweep is idempotent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class DirectiveExpirySweepIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private PreferenceUpdateService preferenceUpdateService;
  @Autowired private PreferenceQueryService preferenceQueryService;
  @Autowired private NutritionUpdateService nutritionUpdateService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_health_directives");
    jdbcTemplate.update("DELETE FROM preference_hard_constraints_audit");
    jdbcTemplate.update("DELETE FROM preference_hard_intolerances");
    jdbcTemplate.update("DELETE FROM preference_dietary_identity_exceptions");
    jdbcTemplate.update("DELETE FROM preference_age_restrictions");
    jdbcTemplate.update("DELETE FROM preference_hard_constraints");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "dir-expiry-" + AuthTestData.shortId();
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

  /** Post + accept a temporary preference_model ingredient-restriction directive; return its id. */
  private UUID acceptTemporaryRestriction(
      AuthedUser user, String externalId, String target, Instant expiry) throws Exception {
    DirectiveInstructionDocument instr =
        NutritionTestData.instructionFor("restrict_ingredient", target, null);
    InboundHealthDirectiveRequest body =
        NutritionTestData.inboundDirectiveRequest(
            user.userId(),
            externalId,
            "apple-health",
            DirectiveType.INGREDIENT_RESTRICTION,
            instr,
            "preference_model",
            null,
            true,
            expiry);
    MvcResult posted =
        mvc.perform(
                post("/api/v1/nutrition/health-directives/inbound")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    String directiveId =
        objectMapper.readTree(posted.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(
            post("/api/v1/nutrition/health-directives/" + directiveId + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.acceptRequest(null, 0L))))
        .andExpect(status().isOk());
    return UUID.fromString(directiveId);
  }

  private String statusOf(UUID directiveId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM nutrition_health_directives WHERE id = ?::uuid",
        String.class,
        directiveId);
  }

  @Test
  void sweep_expiresAcceptedDirectivePastExpiry_andRevertsTemporaryConstraint() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    // auto_expires_at in the past → eligible for the sweep.
    UUID directiveId =
        acceptTemporaryRestriction(user, "ext-egg", "egg", Instant.parse("2020-01-01T00:00:00Z"));

    // Pre-condition: ACCEPTED + the temporary intolerance is present.
    assertThat(statusOf(directiveId)).isEqualTo("ACCEPTED");
    assertThat(
            preferenceQueryService.getHardConstraints(user.userId()).orElseThrow().intolerances())
        .extracting(HardIntoleranceDto::substance)
        .contains("egg");

    int swept = nutritionUpdateService.sweepExpiredDirectives();

    assertThat(swept).isEqualTo(1);
    assertThat(statusOf(directiveId)).isEqualTo("EXPIRED");
    HardConstraintsDto afterSweep =
        preferenceQueryService.getHardConstraints(user.userId()).orElseThrow();
    assertThat(afterSweep.intolerances())
        .extracting(HardIntoleranceDto::substance)
        .doesNotContain("egg");
  }

  @Test
  void sweep_leavesFutureExpiryDirectiveUntouched() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    UUID directiveId =
        acceptTemporaryRestriction(
            user, "ext-dairy", "dairy", Instant.parse("2099-01-01T00:00:00Z"));

    int swept = nutritionUpdateService.sweepExpiredDirectives();

    assertThat(swept).isZero();
    assertThat(statusOf(directiveId)).isEqualTo("ACCEPTED");
    assertThat(
            preferenceQueryService.getHardConstraints(user.userId()).orElseThrow().intolerances())
        .extracting(HardIntoleranceDto::substance)
        .contains("dairy");
  }

  @Test
  void sweep_isIdempotent_secondRunFindsNothing() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());
    acceptTemporaryRestriction(user, "ext-soy", "soy", Instant.parse("2020-01-01T00:00:00Z"));

    assertThat(nutritionUpdateService.sweepExpiredDirectives()).isEqualTo(1);
    assertThat(nutritionUpdateService.sweepExpiredDirectives()).isZero();
  }
}
