package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import com.example.mealprep.nutrition.api.dto.InboundHealthDirectiveRequest;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
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
 * Integration test for the {@code preference_model} health-directive apply path (nutrition/01j).
 * Runs against the full Spring context + real Postgres, so the real {@code
 * PreferenceDirectiveApplyTarget} {@code @Component} wins over the nutrition {@code
 * NoopDirectiveApplyTarget}. Covers: real-bean-wins (accept → 200, hard constraint written with
 * provenance), atomicity (an unmapped action throws and persists nothing), and the {@code
 * removeTemporaryConstraint} reversal end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class DirectivePreferenceRouteIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private PreferenceUpdateService preferenceUpdateService;
  @Autowired private PreferenceQueryService preferenceQueryService;

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
    String username = "pref-dir-" + AuthTestData.shortId();
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

  private String postInbound(AuthedUser user, InboundHealthDirectiveRequest body) throws Exception {
    MvcResult posted =
        mvc.perform(
                post("/api/v1/nutrition/health-directives/inbound")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(posted.getResponse().getContentAsString()).get("id").asText();
  }

  private InboundHealthDirectiveRequest restrictIngredient(
      UUID userId, String externalId, String target, boolean temporary, Instant expiry) {
    DirectiveInstructionDocument instr =
        NutritionTestData.instructionFor("restrict_ingredient", target, null);
    return NutritionTestData.inboundDirectiveRequest(
        userId,
        externalId,
        "apple-health",
        DirectiveType.INGREDIENT_RESTRICTION,
        instr,
        "preference_model",
        null,
        temporary,
        expiry);
  }

  // ---------------- real bean wins + hard constraint written ----------------

  @Test
  void accept_ingredientRestriction_writesHardConstraint_directiveAccepted() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());
    Instant expiry = Instant.parse("2026-09-15T00:00:00Z");

    String directiveId =
        postInbound(user, restrictIngredient(user.userId(), "ext-egg", "egg", true, expiry));

    mvc.perform(
            post("/api/v1/nutrition/health-directives/" + directiveId + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.acceptRequest(null, 0L))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    HardConstraintsDto constraints =
        preferenceQueryService.getHardConstraints(user.userId()).orElseThrow();
    assertThat(constraints.intolerances())
        .extracting(com.example.mealprep.preference.api.dto.HardIntoleranceDto::substance)
        .contains("egg");
    // @Version bumped by the updateHardConstraints write (was 0 after initialise).
    assertThat(constraints.version()).isGreaterThan(0L);

    // Audit row for the intolerances field change.
    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM preference_hard_constraints_audit a"
                + " JOIN preference_hard_constraints hc ON hc.id = a.hard_constraints_id"
                + " WHERE hc.user_id = ? AND a.field_changed = 'intolerances'",
            Long.class,
            user.userId());
    assertThat(auditCount).isEqualTo(1L);

    // Provenance stamped (temporary == true).
    Long stampedCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM preference_hard_intolerances hi"
                + " JOIN preference_hard_constraints hc ON hc.id = hi.hard_constraints_id"
                + " WHERE hc.user_id = ? AND hi.substance = 'egg'"
                + " AND hi.source_directive_id = ?::uuid AND hi.auto_expires_at IS NOT NULL",
            Long.class,
            user.userId(),
            directiveId);
    assertThat(stampedCount).isEqualTo(1L);
  }

  // ---------------- atomicity: unmapped action throws, nothing persists ----------------

  @Test
  void accept_unmappedAction_returns422_directivePendingAndNoHardConstraintWritten()
      throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    // adjust_target is not a preference hard-constraint action → InvalidDirectivePreferenceRoute.
    DirectiveInstructionDocument instr =
        NutritionTestData.instructionFor("adjust_target", "protein_floor_g", null);
    InboundHealthDirectiveRequest body =
        NutritionTestData.inboundDirectiveRequest(
            user.userId(),
            "ext-bad",
            "apple-health",
            DirectiveType.INGREDIENT_RESTRICTION,
            instr,
            "preference_model",
            null,
            false,
            null);
    String directiveId = postInbound(user, body);

    mvc.perform(
            post("/api/v1/nutrition/health-directives/" + directiveId + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.acceptRequest(null, 0L))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(org.hamcrest.Matchers.endsWith("invalid-directive-preference-route")));

    // Directive rolled back to PENDING_REVIEW; no intolerance written (atomicity).
    String dirStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM nutrition_health_directives WHERE id = ?::uuid",
            String.class,
            directiveId);
    assertThat(dirStatus).isEqualTo("PENDING_REVIEW");
    Long intoleranceCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM preference_hard_intolerances hi"
                + " JOIN preference_hard_constraints hc ON hc.id = hi.hard_constraints_id"
                + " WHERE hc.user_id = ?",
            Long.class,
            user.userId());
    assertThat(intoleranceCount).isEqualTo(0L);
  }

  // ---------------- removeTemporaryConstraint reverses ----------------

  @Test
  void removeTemporaryConstraint_reversesTheDirectiveAddition_thenSecondCallIsNoOp()
      throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());
    Instant expiry = Instant.parse("2026-09-15T00:00:00Z");

    String directiveId =
        postInbound(
            user, restrictIngredient(user.userId(), "ext-shellfish", "shellfish", true, expiry));
    mvc.perform(
            post("/api/v1/nutrition/health-directives/" + directiveId + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.acceptRequest(null, 0L))))
        .andExpect(status().isOk());

    UUID directiveUuid = UUID.fromString(directiveId);
    long versionBeforeRemoval =
        preferenceQueryService.getHardConstraints(user.userId()).orElseThrow().version();

    preferenceUpdateService.removeTemporaryConstraint(user.userId(), directiveUuid);

    HardConstraintsDto afterRemoval =
        preferenceQueryService.getHardConstraints(user.userId()).orElseThrow();
    assertThat(afterRemoval.intolerances())
        .extracting(com.example.mealprep.preference.api.dto.HardIntoleranceDto::substance)
        .doesNotContain("shellfish");
    assertThat(afterRemoval.version()).isGreaterThan(versionBeforeRemoval);

    // Second call is idempotent — no further version bump, no throw.
    preferenceUpdateService.removeTemporaryConstraint(user.userId(), directiveUuid);
    HardConstraintsDto afterSecond =
        preferenceQueryService.getHardConstraints(user.userId()).orElseThrow();
    assertThat(afterSecond.version()).isEqualTo(afterRemoval.version());
  }
}
