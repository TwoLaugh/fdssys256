package com.example.mealprep.planner;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Full-context auth + read-shape IT for {@link
 * com.example.mealprep.planner.api.controller.AdminPlannerDecisionsController} (planner-01l).
 * Drives the real deny-by-default {@code AuthSecurityConfig} chain.
 *
 * <p><b>Auth scope note:</b> the project does not enable Spring method-security yet
 * ({@code @EnableMethodSecurity} is absent — see {@code core.audit.AdminDecisionLogController} +
 * {@code PlannerAuth} javadoc), so {@code @PreAuthorize("hasRole('ADMIN')")} is presently inert:
 * the filter chain enforces anonymous &rarr; 401, but every <em>authenticated</em> user passes the
 * role gate (the flat v1 user model has no admin authority). A genuine non-admin&rarr;403 case is
 * therefore not assertable in this codebase today; {@link
 * com.example.mealprep.planner.DecisionLogWriterTest} / a controller-annotation check guard the
 * annotation so it activates the moment method-security lands. This mirrors the existing {@code
 * DecisionLogControllerIT} stance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class AdminPlannerDecisionsControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private AuthProperties authProperties;
  @Autowired private DecisionLogService decisionLogService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    // Single DELETE — decision_log self-FKs on parent_decision_id.
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM auth_sessions");
    jdbcTemplate.update("DELETE FROM auth_login_attempts");
    jdbcTemplate.update("DELETE FROM auth_users");
  }

  private Cookie registerAndLogin() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("admin-dec-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return result.getResponse().getCookie(authProperties.cookieName());
  }

  /** Seed a PLANNER-scope decision row for {@code planId} on {@code traceId}. */
  private UUID seedPlannerRow(
      UUID planId, UUID traceId, UUID parent, String kind, String reasoning) {
    ObjectNode inputs = objectMapper.createObjectNode();
    inputs.put("kind", kind);
    return decisionLogService.write(
        new DecisionLogWriteRequest(
            traceId,
            parent,
            "PLANNER",
            planId,
            DecisionLogScale.WEEK,
            "user",
            null,
            inputs,
            null,
            objectMapper.createObjectNode().put("ok", true),
            reasoning,
            null,
            0,
            null));
  }

  @Test
  void getChain_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/admin/planner/decisions/{planId}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void getChain_returns200_orderedChain_forAuthenticatedUser() throws Exception {
    Cookie cookie = registerAndLogin();
    UUID planId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UUID start = seedPlannerRow(planId, traceId, null, "PLAN_GENERATION_START", "entry");
    UUID stageA = seedPlannerRow(planId, traceId, start, "STAGE_A_DONE", "stage a");
    seedPlannerRow(planId, traceId, stageA, "STAGE_C_DONE", "stage c");
    // A different plan's row must NOT leak into this plan's chain.
    seedPlannerRow(UUID.randomUUID(), UUID.randomUUID(), null, "PLAN_GENERATION_START", "other");

    mvc.perform(get("/api/v1/admin/planner/decisions/{planId}", planId).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.planId").value(planId.toString()))
        .andExpect(jsonPath("$.rows.length()").value(3))
        // created_at ascending: START first, then STAGE_A, then STAGE_C (DAG reconstructable).
        .andExpect(jsonPath("$.rows[0].kind").value("PLAN_GENERATION_START"))
        .andExpect(jsonPath("$.rows[0].parentDecisionId").doesNotExist())
        .andExpect(jsonPath("$.rows[1].kind").value("STAGE_A_DONE"))
        .andExpect(jsonPath("$.rows[1].parentDecisionId").value(start.toString()))
        .andExpect(jsonPath("$.rows[2].kind").value("STAGE_C_DONE"))
        .andExpect(jsonPath("$.rows[2].parentDecisionId").value(stageA.toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getChain_returns200_emptyList_forPlanWithNoRows() throws Exception {
    // Invariant #12: plans generated before 01l (or never) -> empty list, no backfill.
    Cookie cookie = registerAndLogin();
    UUID planId = UUID.randomUUID();

    mvc.perform(get("/api/v1/admin/planner/decisions/{planId}", planId).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").value(planId.toString()))
        .andExpect(jsonPath("$.rows.length()").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getChain_traceIdFilter_narrowsToThatTrace_andPlannerScope() throws Exception {
    Cookie cookie = registerAndLogin();
    UUID planId = UUID.randomUUID();
    UUID traceA = UUID.randomUUID();
    UUID traceB = UUID.randomUUID();
    seedPlannerRow(planId, traceA, null, "PLAN_GENERATION_START", "trace A");
    seedPlannerRow(planId, traceB, null, "PLAN_GENERATION_START", "trace B");

    mvc.perform(
            get("/api/v1/admin/planner/decisions/{planId}", planId)
                .param("traceId", traceA.toString())
                .cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].traceId").value(traceA.toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }
}
