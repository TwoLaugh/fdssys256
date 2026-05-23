package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.mealprep.nutrition.api.dto.InboundHealthDirectiveRequest;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
 * HTTP flow over the health-directives queue. Covers inbound 201 + idempotent 409, accept happy +
 * safety-block, reject 200, and the {@code preference_model} route returning 422 with {@code
 * NoopDirectiveApplyTarget}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class HealthDirectivesFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private PreferenceUpdateService preferenceUpdateService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_health_directives");
    jdbcTemplate.update("DELETE FROM nutrition_per_meal_distribution");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_activity_adjustment");
    jdbcTemplate.update("DELETE FROM nutrition_eating_window");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    // 01j: the preference_model accept path writes hard constraints + audit; clear them too.
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
    String username = "dir-" + AuthTestData.shortId();
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

  /**
   * Seed a {@link com.example.mealprep.nutrition.domain.entity.NutritionTargets} row directly via
   * JDBC so the accept-flow has something to apply against. (01a's update path requires a request
   * shape we don't need to exercise here.)
   */
  private UUID seedNutritionTargets(UUID userId) {
    UUID targetsId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO nutrition_targets (id, user_id, goal, daily_calorie_target,"
            + " calorie_tolerance_under, calorie_tolerance_over, calorie_enforcement,"
            + " calorie_direction, protein_target_g, protein_floor_g, protein_enforcement,"
            + " protein_direction, carbs_target_g, carbs_floor_g, carbs_enforcement,"
            + " carbs_direction, fat_target_g, fat_floor_g, fat_enforcement, fat_direction,"
            + " fibre_target_g, fibre_floor_g, fibre_enforcement, fibre_direction,"
            + " sat_fat_target_g, sat_fat_direction, notes, user_overridden_directions, version,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(), now())",
        targetsId,
        userId,
        Goal.MAINTAIN.name(),
        2000,
        100,
        150,
        "weekly_average",
        EnforcementDirection.BOTH_BOUNDED.name(),
        BigDecimal.valueOf(120.0),
        BigDecimal.valueOf(100.0),
        "daily_floor",
        EnforcementDirection.LOWER_FLOOR.name(),
        BigDecimal.valueOf(250.0),
        null,
        "weekly_average",
        EnforcementDirection.BOTH_BOUNDED.name(),
        BigDecimal.valueOf(70.0),
        null,
        "weekly_average",
        EnforcementDirection.BOTH_BOUNDED.name(),
        BigDecimal.valueOf(30.0),
        null,
        "daily_floor",
        EnforcementDirection.LOWER_FLOOR.name(),
        BigDecimal.valueOf(20.0),
        EnforcementDirection.UPPER_LIMIT.name(),
        null,
        "[]",
        0L);
    return targetsId;
  }

  // ---------------- Inbound ----------------

  @Test
  void inbound_returns401_whenAnonymous() throws Exception {
    InboundHealthDirectiveRequest body =
        NutritionTestData.defaultInboundDirectiveRequest(UUID.randomUUID(), "ext-1");
    mvc.perform(
            post("/api/v1/nutrition/health-directives/inbound")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void inbound_happyPath_returns201_persistsPendingReview() throws Exception {
    AuthedUser user = registerUser();
    InboundHealthDirectiveRequest body =
        NutritionTestData.defaultInboundDirectiveRequest(user.userId(), "ext-happy");

    mvc.perform(
            post("/api/v1/nutrition/health-directives/inbound")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.sourcePlatform").value("apple-health"))
        .andExpect(openApi().isValid(openApiValidator));

    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_health_directives WHERE external_directive_id ="
                + " 'ext-happy'",
            Long.class);
    assertThat(count).isEqualTo(1L);
  }

  @Test
  void inbound_redelivery_returns409_withExistingRowInfo() throws Exception {
    AuthedUser user = registerUser();
    InboundHealthDirectiveRequest body =
        NutritionTestData.defaultInboundDirectiveRequest(user.userId(), "ext-dup");

    mvc.perform(
            post("/api/v1/nutrition/health-directives/inbound")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/nutrition/health-directives/inbound")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("duplicate-health-directive")))
        .andExpect(jsonPath("$.existingStatus").value("PENDING_REVIEW"));
  }

  // ---------------- Reject ----------------

  @Test
  void reject_happyPath_returns200_setsRejected() throws Exception {
    AuthedUser user = registerUser();
    InboundHealthDirectiveRequest body =
        NutritionTestData.defaultInboundDirectiveRequest(user.userId(), "ext-rej");

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

    String rejectBody =
        objectMapper.writeValueAsString(NutritionTestData.rejectRequest("not appropriate", 0L));
    mvc.perform(
            post("/api/v1/nutrition/health-directives/" + directiveId + "/reject")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(rejectBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("not appropriate"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- Accept — safety gate block ----------------

  @Test
  void accept_proposedFloorTooHigh_returns422_safetyBlocked() throws Exception {
    AuthedUser user = registerUser();
    seedNutritionTargets(user.userId());

    // proposedFloor 200 vs current daily target 120 — exceeds 1.2 * 120 = 144 → BLOCK.
    com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument instr =
        NutritionTestData.instructionFor(
            "adjust_target",
            "protein_floor_g",
            NutritionTestData.instructionExtras(
                "proposedFloor", new com.fasterxml.jackson.databind.node.IntNode(200)));
    InboundHealthDirectiveRequest body =
        NutritionTestData.inboundDirectiveRequest(
            user.userId(),
            "ext-blocked",
            "apple-health",
            com.example.mealprep.nutrition.api.dto.DirectiveType.TARGET_ADJUSTMENT,
            instr,
            "nutrition_model",
            "protein_floor_g",
            true,
            Instant.parse("2026-08-01T00:00:00Z"));

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
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(org.hamcrest.Matchers.endsWith("health-directive-safety-gate-blocked")));

    // Directive should remain PENDING_REVIEW with verdict persisted.
    String verdict =
        jdbcTemplate.queryForObject(
            "SELECT safety_gate_verdict FROM nutrition_health_directives WHERE id = ?::uuid",
            String.class,
            directiveId);
    assertThat(verdict).isEqualTo("BLOCKED");
    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM nutrition_health_directives WHERE id = ?::uuid",
            String.class,
            directiveId);
    assertThat(status).isEqualTo("PENDING_REVIEW");
  }

  // ---------------- Accept — preference_model route → 200, real apply (01j) ----------------

  @Test
  void accept_preferenceModelRoute_returns200_addsHardConstraintWithProvenance() throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument instr =
        NutritionTestData.instructionFor("restrict_ingredient", "shellfish", null);
    Instant expiry = Instant.parse("2026-08-01T00:00:00Z");
    InboundHealthDirectiveRequest body =
        NutritionTestData.inboundDirectiveRequest(
            user.userId(),
            "ext-pref",
            "apple-health",
            com.example.mealprep.nutrition.api.dto.DirectiveType.INGREDIENT_RESTRICTION,
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

    // With preference on the classpath the real PreferenceDirectiveApplyTarget wins over the Noop,
    // so the accept succeeds (was 422 with the Noop).
    mvc.perform(
            post("/api/v1/nutrition/health-directives/" + directiveId + "/accept")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.acceptRequest(null, 0L))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(openApi().isValid(openApiValidator));

    // The directive's restriction landed as a hard-constraint intolerance, stamped with the source
    // directive id + expiry (temporary == true).
    Long intoleranceCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM preference_hard_intolerances hi"
                + " JOIN preference_hard_constraints hc ON hc.id = hi.hard_constraints_id"
                + " WHERE hc.user_id = ? AND hi.substance = 'shellfish'"
                + " AND hi.source_directive_id = ?::uuid AND hi.auto_expires_at IS NOT NULL",
            Long.class,
            user.userId(),
            directiveId);
    assertThat(intoleranceCount).isEqualTo(1L);
  }

  @Test
  void accept_preferenceModelRoute_unmappedAction_returns422_directiveStaysPending()
      throws Exception {
    AuthedUser user = registerUser();
    preferenceUpdateService.initialiseHardConstraints(user.userId());

    // adjust_target should route nutrition_model; reaching the preference target with it is a
    // routing bug → clean 422 (not 500), directive stays PENDING_REVIEW.
    com.example.mealprep.nutrition.api.dto.DirectiveInstructionDocument instr =
        NutritionTestData.instructionFor("adjust_target", "protein_floor_g", null);
    InboundHealthDirectiveRequest body =
        NutritionTestData.inboundDirectiveRequest(
            user.userId(),
            "ext-pref-bad",
            "apple-health",
            com.example.mealprep.nutrition.api.dto.DirectiveType.INGREDIENT_RESTRICTION,
            instr,
            "preference_model",
            null,
            false,
            null);

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
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(org.hamcrest.Matchers.endsWith("invalid-directive-preference-route")));

    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM nutrition_health_directives WHERE id = ?::uuid",
            String.class,
            directiveId);
    assertThat(status).isEqualTo("PENDING_REVIEW");
  }

  // ---------------- GET list / single ----------------

  @Test
  void list_returnsOnlyOwnedDirectives() throws Exception {
    AuthedUser user = registerUser();
    InboundHealthDirectiveRequest body =
        NutritionTestData.defaultInboundDirectiveRequest(user.userId(), "ext-list");
    mvc.perform(
            post("/api/v1/nutrition/health-directives/inbound")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/nutrition/health-directives").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].externalDirectiveId").value("ext-list"))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
