package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Block-and-prompt fallback proven end-to-end with the <b>real</b> {@link
 * com.example.mealprep.adaptation.ai.RecipeAdaptationTask} + {@code
 * RecipeAdaptationTaskFactoryImpl} in play (01c's test only exercised the Noop). The {@code
 * AiService} bean throws {@link AiUnavailableException}; the pipeline must wrap it into {@link
 * AdaptationAiUnavailableException} (the project-wide handler maps that to 503), transition the job
 * to {@code FAILED(AI_UNAVAILABLE)}, publish {@code AdaptationJobFailedEvent}, and write NO {@code
 * PendingChange} row.
 *
 * <p>{@code RecipeQueryService} is mocked to return a recipe with a swappable {@code beef}
 * ingredient so Stage A produces candidates and the flow actually reaches the Stage-C dispatch
 * (otherwise it would short-circuit on {@code HARD_FILTER}).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationAiUnavailableE2EIT {

  @Autowired private AdaptationServiceImpl adaptationService;
  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private PendingChangeRepository pendingChangeRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockBean private AiService aiService;
  @MockBean private RecipeQueryService recipeQueryService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_pending_changes");
    jdbcTemplate.update("DELETE FROM adaptation_traces");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
  }

  @Test
  void feedback_job_with_ai_down_fails_ai_unavailable_and_creates_no_pending_change() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();

    when(aiService.execute(any())).thenThrow(new AiUnavailableException("503 upstream"));
    when(recipeQueryService.getById(recipeId))
        .thenReturn(Optional.of(recipe(recipeId, userId, branchId)));
    when(recipeQueryService.getFingerprint(any(), any())).thenReturn(Optional.empty());

    AdaptationJob job =
        jobRepository.saveAndFlush(
            AdaptationJob.builder()
                .id(UUID.randomUUID())
                .recipeId(recipeId)
                .userId(userId)
                .catalogue(Catalogue.USER)
                .source(JobSource.FEEDBACK)
                .priority(JobPriority.SYNC)
                .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
                .status(JobStatus.PENDING)
                .inputs(JsonNodeFactory.instance.objectNode())
                .traceId(UUID.randomUUID())
                .enqueuedAt(Instant.now())
                .build());

    assertThatThrownBy(() -> adaptationService.processJob(job))
        .isInstanceOf(AdaptationAiUnavailableException.class);

    AdaptationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(reloaded.getFailureReason()).isEqualTo(JobFailureReason.AI_UNAVAILABLE);
    assertThat(pendingChangeRepository.count()).isZero();
  }

  private static RecipeDto recipe(UUID recipeId, UUID userId, UUID branchId) {
    RecipeVersionDto version =
        new RecipeVersionDto(
            UUID.randomUUID(),
            branchId,
            1,
            null,
            VersionTrigger.IMPORT,
            "import",
            "CALCULATED",
            Instant.now(),
            "system",
            null,
            List.of(
                new IngredientDto(
                    UUID.randomUUID(),
                    0,
                    "beef",
                    "Beef mince",
                    BigDecimal.valueOf(500),
                    "g",
                    "",
                    false,
                    false,
                    BigDecimal.ONE)),
            List.of(),
            null,
            null,
            null);
    return new RecipeDto(
        recipeId,
        userId,
        Catalogue.USER,
        "Beef Bowl",
        "desc",
        1,
        branchId,
        DataQuality.AI_GENERATED,
        NutritionStatus.CALCULATED,
        null,
        null,
        null,
        null,
        0L,
        Instant.now(),
        Instant.now(),
        version,
        List.of());
  }
}
