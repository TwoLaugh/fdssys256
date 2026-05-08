package com.example.mealprep.ai;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.ai.api.controller.AdminAiController;
import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.api.dto.CostSummaryDto;
import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.service.AdminAiQueryService;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(
    controllers = AdminAiController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class AdminAiControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @MockBean private AdminAiQueryService queryService;
  @MockBean private PromptTemplateService promptTemplateService;

  private static AiCallLogDto sampleCallLog(UUID id) {
    return new AiCallLogDto(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        TaskType.INGREDIENT_MAPPING,
        ModelTier.CHEAP,
        "claude-haiku-4-5",
        "nutrition/usda-mapping",
        1,
        500,
        200,
        0L,
        CallStatus.SUCCEEDED,
        null,
        420,
        Instant.parse("2026-05-08T10:00:00Z"),
        Instant.parse("2026-05-08T10:00:01Z"));
  }

  private static PromptTemplateDto samplePromptTemplate() {
    return new PromptTemplateDto(
        UUID.randomUUID(),
        "loader-fixture",
        1,
        ModelTier.CHEAP,
        "system",
        "user {{x}}",
        JsonNodeFactory.instance.objectNode().put("type", "object"),
        null,
        "Loaded from test-template.md",
        "classpath:prompts/test-template.md",
        "0".repeat(64),
        Instant.parse("2026-05-08T10:00:00Z"));
  }

  @Test
  void costSummary_returns200_andMatchesContract() throws Exception {
    given(queryService.getCostSummary(24))
        .willReturn(
            new CostSummaryDto(
                24, 10L, 0L, List.of(new CostSummaryDto.UserCostEntry(UUID.randomUUID(), 5L, 0L))));

    mvc.perform(get("/api/v1/admin/ai/cost-summary").param("windowHours", "24"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.windowHours", is(24)))
        .andExpect(jsonPath("$.totalCalls", is(10)))
        .andExpect(jsonPath("$.topUsers[0].calls", is(5)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void costSummary_returns400_whenWindowOutOfRange() throws Exception {
    mvc.perform(get("/api/v1/admin/ai/cost-summary").param("windowHours", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void callLog_returns200_andContract() throws Exception {
    given(queryService.getCallLog(any(), any(), any()))
        .willReturn(
            new PageImpl<>(List.of(sampleCallLog(UUID.randomUUID())), PageRequest.of(0, 20), 1));

    mvc.perform(get("/api/v1/admin/ai/call-log"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void callLog_acceptsTaskTypeAndUserIdFilters() throws Exception {
    UUID userId = UUID.randomUUID();
    given(queryService.getCallLog(eq(TaskType.INGREDIENT_MAPPING), eq(userId), any()))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mvc.perform(
            get("/api/v1/admin/ai/call-log")
                .param("taskType", "INGREDIENT_MAPPING")
                .param("userId", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(0)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void promptTemplates_listReturns200_andContract() throws Exception {
    given(promptTemplateService.listAll(any()))
        .willReturn(new PageImpl<>(List.of(samplePromptTemplate()), PageRequest.of(0, 20), 1));

    mvc.perform(get("/api/v1/admin/ai/prompt-templates"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content[0].name", is("loader-fixture")))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void promptTemplate_singleReturns200_andContract() throws Exception {
    given(promptTemplateService.get("loader-fixture", 1)).willReturn(samplePromptTemplate());

    mvc.perform(get("/api/v1/admin/ai/prompt-templates/{name}/{version}", "loader-fixture", 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name", is("loader-fixture")))
        .andExpect(jsonPath("$.version", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void promptTemplate_notFound_returns404() throws Exception {
    given(promptTemplateService.get("missing", 1))
        .willThrow(
            new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "not found"));

    mvc.perform(get("/api/v1/admin/ai/prompt-templates/{name}/{version}", "missing", 1))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }
}
