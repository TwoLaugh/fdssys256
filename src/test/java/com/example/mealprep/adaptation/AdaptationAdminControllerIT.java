package com.example.mealprep.adaptation;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.adaptation.api.controller.AdaptationAdminController;
import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotRetryableException;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Optional;
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

@WebMvcTest(
    controllers = AdaptationAdminController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class AdaptationAdminControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @MockBean private AdaptationService adaptationService;
  @MockBean private AdaptationQueryService queryService;

  private static AdaptationJobDto jobDto(UUID id) {
    return new AdaptationJobDto(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        Catalogue.USER,
        JobSource.FEEDBACK,
        JobPriority.SYNC,
        ApprovalPolicy.PENDING_CHANGE,
        JobStatus.PENDING,
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
  void getJob_returns200_andContract() throws Exception {
    UUID id = UUID.randomUUID();
    given(queryService.getJob(id)).willReturn(Optional.of(jobDto(id)));
    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(id.toString())))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getJob_returns404_whenAbsent() throws Exception {
    UUID id = UUID.randomUUID();
    given(queryService.getJob(id)).willReturn(Optional.empty());
    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}", id))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void recipeJobs_returns200_paged() throws Exception {
    UUID rid = UUID.randomUUID();
    given(queryService.getJobsForRecipe(any(), any()))
        .willReturn(
            new PageImpl<>(java.util.List.of(jobDto(UUID.randomUUID())), PageRequest.of(0, 20), 1));
    mvc.perform(get("/api/v1/adaptation/recipes/{recipeId}/jobs", rid))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void sweepExpiredPending_returns200_withTouchedCount() throws Exception {
    given(adaptationService.sweepExpiredPendingChanges()).willReturn(7);
    mvc.perform(post("/api/v1/adaptation/admin/sweep-expired-pending"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.touched", is(7)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void retryFailedJob_returns200_forFailedJob() throws Exception {
    UUID old = UUID.randomUUID();
    given(adaptationService.retryFailedJob(old)).willReturn(jobDto(UUID.randomUUID()));
    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + old + "\"}"))
        .andExpect(status().isOk())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void retryFailedJob_returns409_forNonFailedJob() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new AdaptationJobNotRetryableException("job is DONE"))
        .when(adaptationService)
        .retryFailedJob(id);
    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + id + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void retryFailedJob_returns404_forMissingJob() throws Exception {
    UUID id = UUID.randomUUID();
    doThrow(new AdaptationJobNotFoundException("not found"))
        .when(adaptationService)
        .retryFailedJob(id);
    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + id + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void promptVersionTraces_returns200_paged() throws Exception {
    given(queryService.getTracesForPromptVersion(any(), any(), any()))
        .willReturn(new PageImpl<>(java.util.List.of(), PageRequest.of(0, 20), 0));
    mvc.perform(
            get(
                "/api/v1/adaptation/admin/prompt-versions/{name}/{version}/traces",
                "recipe-adaptation",
                "v1"))
        .andExpect(status().isOk())
        .andExpect(openApi().isValid(openApiValidator));
  }
}
