package com.example.mealprep.core.audit;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.core.audit.api.controller.AdminDecisionLogController;
import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer + OpenAPI contract test for {@link AdminDecisionLogController}.
 *
 * <p>{@link WebMvcTest} loads only the web slice; the data path is exercised by {@code
 * DecisionLogServiceIT}. Each request/response is validated against {@code
 * src/main/resources/openapi/openapi.yaml} via the {@code openApi()} matcher; a contract drift
 * fails the test.
 *
 * <p>Spring Security autoconfig is excluded so the test exercises the raw controller; the
 * {@code @PreAuthorize} TODO will be revisited once auth-01 wires the filter chain.
 */
@WebMvcTest(
    controllers = AdminDecisionLogController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class DecisionLogControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @MockBean private DecisionLogQueryService queryService;

  private static DecisionLogDto sampleDto(UUID decisionId, UUID traceId) {
    return new DecisionLogDto(
        decisionId,
        traceId,
        null,
        "plan-week",
        UUID.randomUUID(),
        DecisionLogScale.WEEK,
        "user-initiated",
        null,
        JsonNodeFactory.instance.objectNode().put("scoreThreshold", "0.7"),
        JsonNodeFactory.instance.objectNode().put("count", "5"),
        JsonNodeFactory.instance.objectNode().put("index", "2"),
        "variety beat cost",
        null,
        3,
        742,
        Instant.parse("2026-05-07T10:00:00Z"));
  }

  @Test
  void getById_returns200_andMatchesOpenApiSchema() throws Exception {
    UUID decisionId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    given(queryService.getById(decisionId)).willReturn(Optional.of(sampleDto(decisionId, traceId)));

    mvc.perform(get("/api/v1/admin/decision-log/{decisionId}", decisionId))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.decisionId", is(decisionId.toString())))
        .andExpect(jsonPath("$.traceId", is(traceId.toString())))
        .andExpect(jsonPath("$.scopeKind", is("plan-week")))
        .andExpect(jsonPath("$.scale", is("WEEK")))
        .andExpect(jsonPath("$.iteration", is(3)))
        .andExpect(jsonPath("$.durationMs", is(742)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getById_returns404_whenServiceReturnsEmpty() throws Exception {
    UUID decisionId = UUID.randomUUID();
    given(queryService.getById(decisionId)).willReturn(Optional.empty());

    mvc.perform(get("/api/v1/admin/decision-log/{decisionId}", decisionId))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status", is(404)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getByTraceId_returns200_andList() throws Exception {
    UUID traceId = UUID.randomUUID();
    DecisionLogDto a = sampleDto(UUID.randomUUID(), traceId);
    DecisionLogDto b = sampleDto(UUID.randomUUID(), traceId);
    given(queryService.getByTraceId(traceId)).willReturn(List.of(a, b));

    mvc.perform(get("/api/v1/admin/decision-log/trace/{traceId}", traceId))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].traceId", is(traceId.toString())))
        .andExpect(jsonPath("$[1].traceId", is(traceId.toString())))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getByTraceId_returns200WithEmptyList_whenNoneExist() throws Exception {
    UUID traceId = UUID.randomUUID();
    given(queryService.getByTraceId(traceId)).willReturn(List.of());

    mvc.perform(get("/api/v1/admin/decision-log/trace/{traceId}", traceId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getAncestry_returns200_andAncestryResponseShape() throws Exception {
    UUID leaf = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    DecisionLogDto root = sampleDto(UUID.randomUUID(), traceId);
    DecisionLogDto middle = sampleDto(UUID.randomUUID(), traceId);
    given(queryService.getAncestry(any(UUID.class), anyInt()))
        .willReturn(new AncestryResponse(List.of(root, middle), false));

    mvc.perform(get("/api/v1/admin/decision-log/{decisionId}/ancestry", leaf))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.ancestors", hasSize(2)))
        .andExpect(jsonPath("$.cycleDetected", is(false)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getAncestry_acceptsExplicitMaxDepth() throws Exception {
    UUID leaf = UUID.randomUUID();
    given(queryService.getAncestry(any(UUID.class), anyInt()))
        .willReturn(new AncestryResponse(List.of(), false));

    mvc.perform(
            get("/api/v1/admin/decision-log/{decisionId}/ancestry", leaf).param("maxDepth", "8"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ancestors", hasSize(0)))
        .andExpect(jsonPath("$.cycleDetected", is(false)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getAncestry_returns400_whenMaxDepthOutOfRange() throws Exception {
    UUID leaf = UUID.randomUUID();

    // Don't run openApi() matcher here: the request itself deliberately violates the
    // spec's minimum=1 constraint — the validator would (correctly) flag it.
    mvc.perform(
            get("/api/v1/admin/decision-log/{decisionId}/ancestry", leaf).param("maxDepth", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status", equalTo(400)));
  }
}
