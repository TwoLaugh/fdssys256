package com.example.mealprep.adaptation;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.adaptation.api.controller.AdapterRunHistoryController;
import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.recipe.domain.entity.Catalogue;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = AdapterRunHistoryController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class AdapterRunHistoryControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @MockBean private AdaptationQueryService queryService;

  private static AdaptationJobDto jobDto() {
    return new AdaptationJobDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Catalogue.USER,
        JobSource.FEEDBACK,
        JobPriority.SYNC,
        ApprovalPolicy.PENDING_CHANGE,
        JobStatus.DONE,
        null,
        null,
        JsonNodeFactory.instance.objectNode(),
        null,
        UUID.randomUUID(),
        null,
        Instant.parse("2026-05-08T10:00:00Z"),
        null,
        null,
        null,
        0L);
  }

  @Test
  void runHistory_filtersBySourceAndWindow_returns200() throws Exception {
    given(queryService.getRunHistory(eq(JobSource.FEEDBACK), any(), any(), any()))
        .willReturn(new PageImpl<>(List.of(jobDto()), PageRequest.of(0, 20), 1));

    mvc.perform(
            get("/api/v1/adaptation/run-history")
                .param("source", "FEEDBACK")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-12-31T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void runHistory_missingRequiredParam_returns400() throws Exception {
    mvc.perform(get("/api/v1/adaptation/run-history").param("source", "FEEDBACK"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void byPromptVersion_returns200_paged() throws Exception {
    given(queryService.getTracesForPromptVersion(any(), any(), any()))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mvc.perform(
            get("/api/v1/adaptation/run-history/by-prompt-version")
                .param("name", "recipe-adaptation")
                .param("version", "v1"))
        .andExpect(status().isOk())
        .andExpect(openApi().isValid(openApiValidator));
  }
}
