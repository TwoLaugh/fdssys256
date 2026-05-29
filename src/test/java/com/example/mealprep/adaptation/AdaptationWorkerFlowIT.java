package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.entity.ViolationKind;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.SubstitutedItemDto;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.spi.RecipeSubstitutionRecorder;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DB-backed worker-pipeline flow ITs (LLD §Test Plan — {@code Trigger1ImportFlowIT} / {@code
 * Trigger4PlanTimeFlowIT} + safety). Drives the REAL worker ({@link
 * AdaptationServiceImpl#processJob}) with the real {@code RecipeAdaptationTask} factory + invoker;
 * the AI dispatch and the recipe write-seam are mocked at their boundaries (the catalogue's own
 * persistence is covered by recipe-module ITs), while the adaptation tables (jobs, traces) are
 * real.
 *
 * <p>{@code RecipeServiceImpl} implements four SPI interfaces; {@code @MockBean}-ing one evicts the
 * shared bean, so all four are mocked (round-6 multi-interface eviction).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationWorkerFlowIT {

  @Autowired private AdaptationServiceImpl adaptationService;
  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private AdaptationTraceRepository traceRepository;
  @Autowired private PendingChangeRepository pendingChangeRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockBean private AiService aiService;
  @MockBean private RecipeQueryService recipeQueryService;
  @MockBean private RecipeUpdateService recipeUpdateService;
  @MockBean private RecipeSubstitutionRecorder recipeSubstitutionRecorder;
  @MockBean private RecipeWriteApi recipeWriteApi;
  @MockBean private HardConstraintFilterService hardConstraintFilterService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_pending_changes");
    jdbcTemplate.update("DELETE FROM adaptation_traces");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
  }

  // ---------------------------------------------------------------------------------------------
  // Trigger 1 — SYSTEM catalogue → DIRECT apply → a new VERSION is created.
  // ---------------------------------------------------------------------------------------------

  @Test
  void trigger1_systemCatalogue_directApply_createsVersion() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID currentVersionId = UUID.randomUUID();

    when(recipeQueryService.getById(recipeId))
        .thenReturn(Optional.of(recipe(recipeId, userId, branchId, currentVersionId, "beef")));
    when(recipeQueryService.getFingerprint(any(), any())).thenReturn(Optional.empty());
    when(hardConstraintFilterService.checkRecipe(any(), any(), any())).thenReturn(pass());

    ObjectNode diff = swapDiff("beef", "chicken");
    when(aiService.execute(any()))
        .thenReturn(response(0, AdaptationClassification.VERSION, diff, BigDecimal.valueOf(0.9)));
    UUID newVersionId = UUID.randomUUID();
    when(recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(newVersionId, branchId));

    AdaptationJob job =
        seed(
            recipeId,
            userId,
            JobSource.IMPORT,
            JobPriority.ASYNC,
            Catalogue.SYSTEM,
            ApprovalPolicy.DIRECT);

    adaptationService.processJob(job);

    AdaptationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(JobStatus.DONE);

    // A version was actually written through the catalogue write-seam.
    verify(recipeWriteApi).saveAdaptedVersion(any(SaveAdaptedVersionCommand.class));
    verify(recipeWriteApi, never()).saveAdaptedSubstitution(any());

    AdaptationTrace trace = traceRepository.findByJobId(job.getId()).orElseThrow();
    assertThat(trace.getOutcomeKind()).isEqualTo(OutcomeKind.VERSION_CREATED);
    assertThat(trace.getOutcomeTargetId()).isEqualTo(newVersionId);
    // DIRECT applies immediately — no pending change staged for user review.
    assertThat(pendingChangeRepository.count()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Trigger 4 — PLAN_OVERLAY → a SUBSTITUTION overlay is created (master recipe never mutates).
  // ---------------------------------------------------------------------------------------------

  @Test
  void trigger4_planTime_planOverlay_createsSubstitution() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID currentVersionId = UUID.randomUUID();

    when(recipeQueryService.getById(recipeId))
        .thenReturn(Optional.of(recipe(recipeId, userId, branchId, currentVersionId, "beef")));
    when(recipeQueryService.getFingerprint(any(), any())).thenReturn(Optional.empty());
    when(hardConstraintFilterService.checkRecipe(any(), any(), any())).thenReturn(pass());

    ObjectNode diff = swapDiff("beef", "chicken");
    when(aiService.execute(any()))
        .thenReturn(
            response(0, AdaptationClassification.SUBSTITUTION, diff, BigDecimal.valueOf(0.9)));
    UUID subId = UUID.randomUUID();
    when(recipeWriteApi.saveAdaptedSubstitution(any(SaveAdaptedSubstitutionCommand.class)))
        .thenReturn(substitutionDto(subId));

    AdaptationJob job =
        seed(
            recipeId,
            userId,
            JobSource.PLAN_TIME,
            JobPriority.SYNC,
            Catalogue.USER,
            ApprovalPolicy.PLAN_OVERLAY);

    adaptationService.processJob(job);

    AdaptationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(JobStatus.DONE);

    ArgumentCaptor<SaveAdaptedSubstitutionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedSubstitutionCommand.class);
    verify(recipeWriteApi).saveAdaptedSubstitution(cmd.capture());
    assertThat(cmd.getValue().original().ingredientMappingKey()).isEqualTo("beef");
    assertThat(cmd.getValue().substitute().ingredientMappingKey()).isEqualTo("chicken");
    // The master recipe is never mutated by a plan-time refine (LLD §Decisions §9).
    verify(recipeWriteApi, never()).saveAdaptedVersion(any());

    AdaptationTrace trace = traceRepository.findByJobId(job.getId()).orElseThrow();
    assertThat(trace.getOutcomeKind()).isEqualTo(OutcomeKind.SUBSTITUTION_CREATED);
    assertThat(trace.getOutcomeTargetId()).isEqualTo(subId);
  }

  // ---------------------------------------------------------------------------------------------
  // Safety — the Step-6 final-diff re-check blocks an allergen-reintroducing diff before any write.
  // ---------------------------------------------------------------------------------------------

  @Test
  void safety_finalDiffReintroducingAllergen_isBlocked_HARD_FILTER_noWrite() {
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID currentVersionId = UUID.randomUUID();

    when(recipeQueryService.getById(recipeId))
        .thenReturn(Optional.of(recipe(recipeId, userId, branchId, currentVersionId, "beef")));
    when(recipeQueryService.getFingerprint(any(), any())).thenReturn(Optional.empty());
    // Step 3 lets the shortlist through (current "beef" is fine), but anything resolving to "tofu"
    // (a soy allergen) is rejected — which catches the LLM's post-hoc finalDiff at Step 6.
    when(hardConstraintFilterService.checkRecipe(any(), any(), any()))
        .thenAnswer(
            inv -> {
              List<String> keys = inv.getArgument(2);
              return keys.contains("tofu") ? fail() : pass();
            });

    ObjectNode diff = swapDiff("beef", "tofu");
    when(aiService.execute(any()))
        .thenReturn(response(0, AdaptationClassification.VERSION, diff, BigDecimal.valueOf(0.95)));

    AdaptationJob job =
        seed(
            recipeId,
            userId,
            JobSource.IMPORT,
            JobPriority.ASYNC,
            Catalogue.SYSTEM,
            ApprovalPolicy.DIRECT);

    assertThatThrownBy(() -> adaptationService.processJob(job))
        .isInstanceOf(AdaptationHardConstraintViolationException.class);

    AdaptationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(reloaded.getFailureReason()).isEqualTo(JobFailureReason.HARD_FILTER);
    // The unsafe diff was never written through.
    verify(recipeWriteApi, never()).saveAdaptedVersion(any());
    verify(recipeWriteApi, never()).saveAdaptedBranch(any());
    verify(recipeWriteApi, never()).saveAdaptedSubstitution(any());
    assertThat(pendingChangeRepository.count()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static FilterResult pass() {
    return new FilterResult(true, List.of());
  }

  private static FilterResult fail() {
    return new FilterResult(
        false,
        List.of(
            new Violation(
                UUID.randomUUID(), UUID.randomUUID(), "tofu", ViolationKind.ALLERGY, "soy")));
  }

  private static ObjectNode swapDiff(String from, String to) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", from);
    diff.put("to", to);
    return diff;
  }

  private static RecipeAdaptationResponse response(
      int idx, AdaptationClassification cls, ObjectNode diff, BigDecimal confidence) {
    return new RecipeAdaptationResponse(
        idx, cls, "reasoning", "", confidence, BigDecimal.valueOf(0.9), null, diff, List.of());
  }

  private AdaptationJob seed(
      UUID recipeId,
      UUID userId,
      JobSource source,
      JobPriority priority,
      Catalogue catalogue,
      ApprovalPolicy policy) {
    return jobRepository.saveAndFlush(
        AdaptationJob.builder()
            .id(UUID.randomUUID())
            .recipeId(recipeId)
            .userId(userId)
            .catalogue(catalogue)
            .source(source)
            .priority(priority)
            .approvalPolicy(policy)
            .status(JobStatus.PENDING)
            .inputs(JsonNodeFactory.instance.objectNode())
            .traceId(UUID.randomUUID())
            .enqueuedAt(Instant.now())
            .build());
  }

  private static RecipeDto recipe(
      UUID recipeId, UUID userId, UUID branchId, UUID versionId, String ingredientKey) {
    RecipeVersionDto version =
        new RecipeVersionDto(
            versionId,
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
                    ingredientKey,
                    ingredientKey,
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
        Catalogue.SYSTEM,
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
        null,
        0L,
        Instant.now(),
        Instant.now(),
        version,
        List.of());
  }

  private static RecipeVersionDto versionDto(UUID versionId, UUID branchId) {
    return new RecipeVersionDto(
        versionId,
        branchId,
        2,
        UUID.randomUUID(),
        VersionTrigger.ADAPTATION_PIPELINE,
        "adapted",
        "pending",
        Instant.now(),
        "system",
        UUID.randomUUID(),
        List.of(),
        List.of(),
        null,
        null,
        List.of());
  }

  private static RecipeSubstitutionDto substitutionDto(UUID id) {
    return new RecipeSubstitutionDto(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        new SubstitutedItemDto("beef", BigDecimal.ONE, "unit"),
        new SubstitutedItemDto("chicken", BigDecimal.ONE, "unit"),
        SubstitutionReason.DIETARY_TEMP,
        null,
        List.of(),
        "notes",
        true,
        0,
        null,
        SubstitutionState.ACCEPTED,
        null,
        Instant.now(),
        "system",
        UUID.randomUUID(),
        0L);
  }
}
