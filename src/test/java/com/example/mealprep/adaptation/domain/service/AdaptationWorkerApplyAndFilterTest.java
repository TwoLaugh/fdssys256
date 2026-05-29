package com.example.mealprep.adaptation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.AdaptationContextAssembler;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.ai.TriggerInputs;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.NutritionalKnowledgeBundleDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLlmInvoker;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationLockAcquirer;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationTraceWriter;
import com.example.mealprep.adaptation.domain.service.internal.CandidateGenerator;
import com.example.mealprep.adaptation.domain.service.internal.ChangeDimensionResolver;
import com.example.mealprep.adaptation.domain.service.internal.CharacterPreservationGate;
import com.example.mealprep.adaptation.domain.service.internal.ConfidenceFloorGate;
import com.example.mealprep.adaptation.domain.service.internal.IngredientRemoveStrategy;
import com.example.mealprep.adaptation.domain.service.internal.IngredientSwapStrategy;
import com.example.mealprep.adaptation.domain.service.internal.MethodSimplificationStrategy;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeStore;
import com.example.mealprep.adaptation.domain.service.internal.PortionAdjustStrategy;
import com.example.mealprep.adaptation.domain.service.internal.RebaseOrchestrator;
import com.example.mealprep.adaptation.domain.service.internal.ScoringEngine;
import com.example.mealprep.adaptation.event.AdaptationJobCompletedEvent;
import com.example.mealprep.adaptation.event.AdaptationJobFailedEvent;
import com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.lock.LockKey;
import com.example.mealprep.core.lock.LockService;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.api.dto.Violation;
import com.example.mealprep.preference.domain.entity.ViolationKind;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.dto.SubstitutedItemDto;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.exception.RecipeVersionConflictException;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedBranchCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedSubstitutionCommand;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Behavioural coverage for the worker apply paths (adaptation-1 / adaptation-4) and the
 * deterministic hard-constraint safety net at Step 3 + Step 6 (adaptation-2).
 *
 * <p>The wiring uses REAL {@link CandidateGenerator} strategies + a mocked {@link
 * AdaptationContextAssembler} that returns a context carrying a current version with allergen-laden
 * ingredients, so the Stage-A swap strategy emits real swap candidates that the filter then judges.
 */
class AdaptationWorkerApplyAndFilterTest {

  // --------------------------------------------------------------------------------------------
  // adaptation-2 — hard-constraint safety net
  // --------------------------------------------------------------------------------------------

  @Test
  void step3_filter_drops_candidate_that_reintroduces_a_known_allergen() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);

    // The recipe currently has "milk" (the swap strategy will emit milk -> almond_milk / oat_milk).
    // The user is allergic to "almond_milk": the filter must DROP any candidate whose resulting set
    // contains almond_milk, while allowing the oat_milk swap.
    w.stubContextWithIngredients(job, "milk");
    w.stubFilterRejectingKey("almond_milk");
    // Scoring passes through; no auto-skip; LLM picks index 0 of whatever survives.
    w.stubScoringPassthrough();
    w.stubLlmPicksFirstSurvivingCandidate();

    w.service.processJob(job);

    // The almond_milk candidate was dropped at Step 3, so it never reached the LLM context.
    AdaptationContext seen = w.captureLlmContext();
    assertThat(seen.candidates())
        .allSatisfy(
            c ->
                assertThat(c.proposedDiff().path("to").asText())
                    .as("almond_milk candidate must be dropped before scoring")
                    .isNotEqualTo("almond_milk"));
    // The job still completes (oat_milk is feasible) — a pending change is created, not a failure.
    verify(w.events, never()).publishEvent(any(AdaptationJobFailedEvent.class));
    verify(w.events).publishEvent(any(AdaptationJobCompletedEvent.class));
  }

  @Test
  void step3_filter_empty_after_filter_fails_job_HARD_FILTER() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.USER, ApprovalPolicy.PENDING_CHANGE);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "milk");
    w.stubScoringPassthrough();
    // The user is allergic to BOTH almond_milk and oat_milk — every swap candidate is infeasible.
    // (portion-adjust / method candidates keep the base "milk", which we also reject below.)
    when(w.filter.checkRecipe(any(), any(), anyList()))
        .thenReturn(failResult()); // everything fails the safety net

    assertThat(w.runAndCaptureFailureReason(job)).isEqualTo(JobFailureReason.HARD_FILTER);
    // LLM never invoked — no feasible shortlist to choose from.
    verify(w.llmInvoker, never()).invoke(any(), any());
  }

  @Test
  void step6_recheck_blocks_final_diff_that_reintroduces_allergen() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "beef");
    w.stubScoringPassthrough();

    // Step 3 lets the candidate set through (current "beef" is fine for this user). But the LLM
    // returns a free-form finalDiff that swaps to "tofu" — a soy allergen the user can't have. The
    // Step-6 recheck must catch the post-hoc stitch and fail HARD_FILTER, never reaching the write.
    when(w.filter.checkRecipe(any(), any(), anyList()))
        .thenAnswer(
            inv -> {
              List<String> keys = inv.getArgument(2);
              return keys.contains("tofu") ? failResult() : pass();
            });
    ObjectNode finalDiff = JsonNodeFactory.instance.objectNode();
    finalDiff.put("kind", "ingredient-swap");
    finalDiff.put("from", "beef");
    finalDiff.put("to", "tofu");
    when(w.scoringEngine.shouldAutoSkipStageC(any())).thenReturn(false);
    when(w.llmInvoker.invoke(any(), any()))
        .thenReturn(
            new RecipeAdaptationResponse(
                0,
                AdaptationClassification.VERSION,
                "swap beef for tofu",
                "",
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(0.9),
                null,
                finalDiff,
                List.of()));

    assertThat(w.runAndCaptureFailureReason(job)).isEqualTo(JobFailureReason.HARD_FILTER);
    // The unsafe diff was never written through to the catalogue.
    verify(w.recipeWriteApi, never()).saveAdaptedVersion(any());
    verify(w.recipeWriteApi, never()).saveAdaptedBranch(any());
  }

  // --------------------------------------------------------------------------------------------
  // adaptation-1 + adaptation-4 — DIRECT / PLAN_OVERLAY apply paths
  // --------------------------------------------------------------------------------------------

  @Test
  void direct_system_catalogue_job_creates_a_version_through_rebase_orchestrator() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "beef");
    w.stubFilterAllPass();
    w.stubScoringPassthrough();
    w.stubLlmPicksFirstSurvivingCandidate();
    UUID newVersionId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(newVersionId, UUID.randomUUID()));

    w.service.processJob(job);

    // Version written THROUGH the orchestrator (which delegates to RecipeWriteApi).
    verify(w.recipeWriteApi, times(1)).saveAdaptedVersion(any(SaveAdaptedVersionCommand.class));
    ArgumentCaptor<AdaptationJobCompletedEvent> ev =
        ArgumentCaptor.forClass(AdaptationJobCompletedEvent.class);
    verify(w.events).publishEvent(ev.capture());
    assertThat(ev.getValue().outcomeKind()).isEqualTo(OutcomeKind.VERSION_CREATED);
    assertThat(ev.getValue().outcomeTargetId()).isEqualTo(newVersionId);
    // Never staged a pending change — DIRECT applies immediately.
    verify(w.pendingChangeStore, never()).create(any(), any(), any(), any(), any(), any());
  }

  @Test
  void direct_apply_retries_on_version_conflict_then_succeeds() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "beef");
    w.stubFilterAllPass();
    w.stubScoringPassthrough();
    w.stubLlmPicksFirstSurvivingCandidate();
    UUID newVersionId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenThrow(new RecipeVersionConflictException("head moved"))
        .thenReturn(versionDto(newVersionId, UUID.randomUUID()));

    w.service.processJob(job);

    // Two attempts: first conflicts, orchestrator rebases, second succeeds.
    verify(w.recipeWriteApi, times(2)).saveAdaptedVersion(any(SaveAdaptedVersionCommand.class));
    verify(w.events).publishEvent(any(AdaptationJobCompletedEvent.class));
  }

  @Test
  void direct_apply_exhausting_rebases_fails_job_REBASE_EXHAUSTED() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "beef");
    w.stubFilterAllPass();
    w.stubScoringPassthrough();
    w.stubLlmPicksFirstSurvivingCandidate();
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenThrow(new RecipeVersionConflictException("head moved"));

    assertThatThrownBy(() -> w.service.processJob(job))
        .isInstanceOf(com.example.mealprep.adaptation.exception.RebaseExhaustedException.class);
    // maxRebaseAttempts = 3 → exactly 3 attempts before giving up.
    verify(w.recipeWriteApi, times(3)).saveAdaptedVersion(any(SaveAdaptedVersionCommand.class));
    ArgumentCaptor<AdaptationJobFailedEvent> ev =
        ArgumentCaptor.forClass(AdaptationJobFailedEvent.class);
    verify(w.events).publishEvent(ev.capture());
    assertThat(ev.getValue().reason()).isEqualTo(JobFailureReason.REBASE_EXHAUSTED);
  }

  @Test
  void plan_overlay_job_creates_a_substitution_not_a_version() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.PLAN_TIME, Catalogue.USER, ApprovalPolicy.PLAN_OVERLAY);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "beef");
    w.stubFilterAllPass();
    w.stubScoringPassthrough();
    // LLM picks a swap candidate (beef -> chicken).
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", "beef");
    diff.put("to", "chicken");
    when(w.scoringEngine.shouldAutoSkipStageC(any())).thenReturn(false);
    when(w.llmInvoker.invoke(any(), any()))
        .thenReturn(
            new RecipeAdaptationResponse(
                0,
                AdaptationClassification.SUBSTITUTION,
                "swap beef for chicken",
                "",
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(0.9),
                null,
                diff,
                List.of()));
    UUID subId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedSubstitution(any(SaveAdaptedSubstitutionCommand.class)))
        .thenReturn(substitutionDto(subId));

    w.service.processJob(job);

    ArgumentCaptor<SaveAdaptedSubstitutionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedSubstitutionCommand.class);
    verify(w.recipeWriteApi).saveAdaptedSubstitution(cmd.capture());
    assertThat(cmd.getValue().original().ingredientMappingKey()).isEqualTo("beef");
    assertThat(cmd.getValue().substitute().ingredientMappingKey()).isEqualTo("chicken");
    verify(w.recipeWriteApi, never()).saveAdaptedVersion(any());
    ArgumentCaptor<AdaptationJobCompletedEvent> ev =
        ArgumentCaptor.forClass(AdaptationJobCompletedEvent.class);
    verify(w.events).publishEvent(ev.capture());
    assertThat(ev.getValue().outcomeKind()).isEqualTo(OutcomeKind.SUBSTITUTION_CREATED);
    assertThat(ev.getValue().outcomeTargetId()).isEqualTo(subId);
  }

  @Test
  void direct_branch_classification_creates_a_branch() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    w.stubContextWithIngredients(job, "beef");
    w.stubFilterAllPass();
    w.stubScoringPassthrough();
    // Character-preservation gate forces a branch (low score) — the AI also classified BRANCH.
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", "beef");
    diff.put("to", "chicken");
    when(w.scoringEngine.shouldAutoSkipStageC(any())).thenReturn(false);
    when(w.llmInvoker.invoke(any(), any()))
        .thenReturn(
            new RecipeAdaptationResponse(
                0,
                AdaptationClassification.BRANCH,
                "diverges enough to branch",
                "",
                BigDecimal.valueOf(0.9),
                BigDecimal.valueOf(0.4), // < 0.6 char-preservation → forceBranch
                null,
                diff,
                List.of()));
    UUID branchVersionId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedBranch(any(SaveAdaptedBranchCommand.class)))
        .thenReturn(branchDto(branchVersionId));

    w.service.processJob(job);

    verify(w.recipeWriteApi).saveAdaptedBranch(any(SaveAdaptedBranchCommand.class));
    verify(w.recipeWriteApi, never()).saveAdaptedVersion(any());
    ArgumentCaptor<AdaptationJobCompletedEvent> ev =
        ArgumentCaptor.forClass(AdaptationJobCompletedEvent.class);
    verify(w.events).publishEvent(ev.capture());
    assertThat(ev.getValue().outcomeKind()).isEqualTo(OutcomeKind.BRANCH_CREATED);
  }

  // --------------------------------------------------------------------------------------------
  // resultingIngredientKeys — the diff -> resulting-key-set resolver the safety net checks against
  // --------------------------------------------------------------------------------------------

  @Test
  void resultingKeys_swap_removesFrom_addsTo() {
    Wiring w = new Wiring();
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", "milk");
    diff.put("to", "almond_milk");
    List<String> result = w.service.resultingIngredientKeys(List.of("milk", "flour"), diff);
    assertThat(result).containsExactlyInAnyOrder("almond_milk", "flour").doesNotContain("milk");
  }

  @Test
  void resultingKeys_remove_dropsKey() {
    Wiring w = new Wiring();
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-remove");
    diff.put("key", "milk");
    List<String> result = w.service.resultingIngredientKeys(List.of("milk", "flour"), diff);
    assertThat(result).containsExactly("flour");
  }

  @Test
  void resultingKeys_portionAdjust_leavesKeysUnchanged() {
    Wiring w = new Wiring();
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "portion-adjust");
    diff.put("factor", 1.5);
    List<String> result = w.service.resultingIngredientKeys(List.of("milk", "flour"), diff);
    assertThat(result).containsExactlyInAnyOrder("milk", "flour");
  }

  @Test
  void resultingKeys_nullDiff_returnsBaseKeysUnchanged() {
    Wiring w = new Wiring();
    assertThat(w.service.resultingIngredientKeys(List.of("milk"), null)).containsExactly("milk");
    assertThat(
            w.service.resultingIngredientKeys(List.of("milk"), JsonNodeFactory.instance.nullNode()))
        .containsExactly("milk");
  }

  @Test
  void resultingKeys_ingredientChangesArray_addsIntroducedKey_andRemovesRemovedKey() {
    Wiring w = new Wiring();
    // A free-form LLM finalDiff shaped like RecipeDiffDto.ingredientChanges[]: ADD tofu, REMOVE
    // beef.
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    var changes = diff.putArray("ingredientChanges");
    ObjectNode add = changes.addObject();
    add.put("action", "ADDED");
    add.putObject("to").put("ingredientMappingKey", "tofu");
    ObjectNode rem = changes.addObject();
    rem.put("action", "REMOVED");
    rem.putObject("from").put("ingredientMappingKey", "beef");
    List<String> result = w.service.resultingIngredientKeys(List.of("beef", "onion"), diff);
    assertThat(result).containsExactlyInAnyOrder("onion", "tofu").doesNotContain("beef");
  }

  @Test
  void direct_apply_withNullCurrentVersion_stillWritesVersion_withNullParentExpectations() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.IMPORT, Catalogue.SYSTEM, ApprovalPolicy.DIRECT);
    w.stubCommon(job);
    // Context with a current version (so Stage A produces candidates), but applyDirect is invoked
    // directly below with a NULL-currentVersion context to exercise the null-guard branches.
    w.stubContextWithIngredients(job, "beef");
    UUID newVersionId = UUID.randomUUID();
    when(w.recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(newVersionId, UUID.randomUUID()));

    AdaptationContext nullVersionCtx =
        new AdaptationContext(
            "IMPORT",
            null,
            null, // no current version
            null,
            List.of(),
            null,
            "hc:test",
            null,
            new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
            null,
            null,
            null,
            null);
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", "beef");
    diff.put("to", "chicken");
    RecipeAdaptationResponse response =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.VERSION,
            "r",
            "",
            BigDecimal.valueOf(0.9),
            BigDecimal.valueOf(0.9),
            null,
            diff,
            List.of());

    w.service.applyDirect(job, nullVersionCtx, response, AdaptationClassification.VERSION);

    ArgumentCaptor<SaveAdaptedVersionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedVersionCommand.class);
    verify(w.recipeWriteApi).saveAdaptedVersion(cmd.capture());
    // Null current version → null branch/parent expectations + 0 parent version number.
    assertThat(cmd.getValue().branchId()).isNull();
    assertThat(cmd.getValue().expectedParentVersionId()).isNull();
    assertThat(cmd.getValue().expectedParentVersionNumber()).isZero();
    assertThat(cmd.getValue().adapterTraceId()).isEqualTo(job.getTraceId());
  }

  @Test
  void planOverlay_diffMissingFromTo_throwsHardConstraintViolation_noWrite() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.PLAN_TIME, Catalogue.USER, ApprovalPolicy.PLAN_OVERLAY);
    // finalDiff has no from/to keys → malformed plan-overlay candidate.
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "portion-adjust");
    RecipeAdaptationResponse response =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.SUBSTITUTION,
            "r",
            "",
            BigDecimal.valueOf(0.9),
            BigDecimal.valueOf(0.9),
            null,
            diff,
            List.of());

    assertThatThrownBy(() -> w.service.applyPlanOverlay(job, null, null, response))
        .isInstanceOf(AdaptationHardConstraintViolationException.class);
    verify(w.recipeWriteApi, never()).saveAdaptedSubstitution(any());
  }

  @Test
  void planOverlay_nullCurrentVersion_usesRecipeIdAsVersionId() {
    Wiring w = new Wiring();
    AdaptationJob job = job(JobSource.PLAN_TIME, Catalogue.USER, ApprovalPolicy.PLAN_OVERLAY);
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", "beef");
    diff.put("to", "chicken");
    RecipeAdaptationResponse response =
        new RecipeAdaptationResponse(
            0,
            AdaptationClassification.SUBSTITUTION,
            "r",
            "",
            BigDecimal.valueOf(0.9),
            BigDecimal.valueOf(0.9),
            null,
            diff,
            List.of());
    when(w.recipeWriteApi.saveAdaptedSubstitution(any(SaveAdaptedSubstitutionCommand.class)))
        .thenReturn(substitutionDto(UUID.randomUUID()));

    w.service.applyPlanOverlay(job, null, null, response);

    ArgumentCaptor<SaveAdaptedSubstitutionCommand> cmd =
        ArgumentCaptor.forClass(SaveAdaptedSubstitutionCommand.class);
    verify(w.recipeWriteApi).saveAdaptedSubstitution(cmd.capture());
    // Null current version → versionId falls back to the recipeId.
    assertThat(cmd.getValue().versionId()).isEqualTo(job.getRecipeId());
    assertThat(cmd.getValue().reason()).isEqualTo(SubstitutionReason.DIETARY_TEMP);
    assertThat(cmd.getValue().temporary()).isTrue();
  }

  // --------------------------------------------------------------------------------------------
  // triggerInputsFromJob — source-bias payload is hydrated from the persisted inputs JSONB
  // --------------------------------------------------------------------------------------------

  @Test
  void triggerInputs_feedback_parsesRatingDeltaAndText() {
    Wiring w = new Wiring();
    ObjectNode inputs = JsonNodeFactory.instance.objectNode();
    inputs.put("feedbackText", "too salty");
    ObjectNode rd = inputs.putObject("ratingDelta");
    rd.put("taste", -0.8);
    rd.put("effortWorthIt", 0.1);
    AdaptationJob job =
        jobWithInputs(JsonNodeFactory.instance.objectNode(), JobSource.FEEDBACK, inputs);
    TriggerInputs ti = w.service.triggerInputsFromJob(job);
    assertThat(ti.feedbackText()).isEqualTo("too salty");
    assertThat(ti.ratingDelta()).isNotNull();
    assertThat(ti.ratingDelta().taste()).isEqualByComparingTo(BigDecimal.valueOf(-0.8));
  }

  @Test
  void triggerInputs_planTime_parsesDirective() {
    Wiring w = new Wiring();
    ObjectNode inputs = JsonNodeFactory.instance.objectNode();
    ObjectNode dir = inputs.putObject("directive");
    dir.put("kind", "COST_DELTA");
    dir.put("description", "drop £2");
    AdaptationJob job =
        jobWithInputs(JsonNodeFactory.instance.objectNode(), JobSource.PLAN_TIME, inputs);
    TriggerInputs ti = w.service.triggerInputsFromJob(job);
    assertThat(ti.directive()).isNotNull();
    assertThat(ti.directive().description()).isEqualTo("drop £2");
  }

  @Test
  void triggerInputs_feedback_missingFields_yieldsNulls_notCrash() {
    Wiring w = new Wiring();
    AdaptationJob job =
        jobWithInputs(
            JsonNodeFactory.instance.objectNode(),
            JobSource.FEEDBACK,
            JsonNodeFactory.instance.objectNode());
    TriggerInputs ti = w.service.triggerInputsFromJob(job);
    assertThat(ti.feedbackText()).isNull();
    assertThat(ti.ratingDelta()).isNull();
  }

  private static AdaptationJob jobWithInputs(
      com.fasterxml.jackson.databind.JsonNode ignored, JobSource source, ObjectNode inputs) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(source)
        .priority(JobPriority.SYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.RUNNING)
        .inputs(inputs)
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }

  // --------------------------------------------------------------------------------------------
  // helpers
  // --------------------------------------------------------------------------------------------

  private static FilterResult pass() {
    return new FilterResult(true, List.of());
  }

  private static FilterResult failResult() {
    return new FilterResult(
        false,
        List.of(
            new Violation(
                UUID.randomUUID(), UUID.randomUUID(), "x", ViolationKind.ALLERGY, "allergen")));
  }

  private static AdaptationJob job(JobSource source, Catalogue catalogue, ApprovalPolicy policy) {
    return AdaptationJob.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(catalogue)
        .source(source)
        .priority(source == JobSource.IMPORT ? JobPriority.ASYNC : JobPriority.SYNC)
        .approvalPolicy(policy)
        .status(JobStatus.RUNNING)
        .inputs(JsonNodeFactory.instance.objectNode())
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
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

  private static RecipeBranchDto branchDto(UUID branchId) {
    return new RecipeBranchDto(
        branchId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "branch",
        "label",
        "reason",
        1,
        BigDecimal.ZERO,
        Instant.now(),
        "system",
        UUID.randomUUID(),
        0L);
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

  private static final class Wiring {
    final AdaptationJobRepository jobRepository = mock(AdaptationJobRepository.class);
    final ScoringEngine scoringEngine = mock(ScoringEngine.class);
    final AdaptationLlmInvoker llmInvoker = mock(AdaptationLlmInvoker.class);
    final PendingChangeStore pendingChangeStore = mock(PendingChangeStore.class);
    final AdaptationTraceWriter traceWriter = mock(AdaptationTraceWriter.class);
    final LockService lockService = mock(LockService.class);
    final DecisionLogService decisionLogService = mock(DecisionLogService.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final RecipeWriteApi recipeWriteApi = mock(RecipeWriteApi.class);
    final AdaptationContextAssembler contextAssembler = mock(AdaptationContextAssembler.class);
    final HardConstraintFilterService filter = mock(HardConstraintFilterService.class);
    final AdaptationServiceImpl service;

    Wiring() {
      AdaptationConfig config =
          new AdaptationConfig(
              5,
              10_000,
              8_000,
              12_000,
              3,
              3,
              14,
              new BigDecimal("0.50"),
              new BigDecimal("2.00"),
              null,
              30,
              "0 0 4 * * *",
              "0 30 4 * * *");
      CandidateGenerator candidateGenerator =
          new CandidateGenerator(
              new IngredientSwapStrategy(),
              new PortionAdjustStrategy(),
              new MethodSimplificationStrategy(),
              new IngredientRemoveStrategy());
      RebaseOrchestrator rebaseOrchestrator = new RebaseOrchestrator(recipeWriteApi, config);
      AdaptationLockAcquirer lockAcquirer = new AdaptationLockAcquirer(lockService, jobRepository);
      this.service =
          new AdaptationServiceImpl(
              jobRepository,
              mock(PendingChangeRepository.class),
              mock(AdaptationTraceRepository.class),
              mock(AdaptationFingerprintRepository.class),
              mock(PlannerHintRecordRepository.class),
              mock(NutritionalKnowledgeRepository.class),
              candidateGenerator,
              scoringEngine,
              llmInvoker,
              new ConfidenceFloorGate(config),
              new CharacterPreservationGate(),
              pendingChangeStore,
              new ChangeDimensionResolver(),
              traceWriter,
              decisionLogService,
              events,
              recipeWriteApi,
              null,
              config,
              contextAssembler,
              null,
              null,
              null,
              null,
              null,
              lockAcquirer,
              rebaseOrchestrator,
              filter);
    }

    void stubCommon(AdaptationJob job) {
      when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
      when(lockService.tryAcquire(any(LockKey.class))).thenReturn(true);
      when(pendingChangeStore.create(any(), any(), any(), any(), any(), any()))
          .thenReturn(UUID.randomUUID());
    }

    /**
     * Mock the assembler to return a context whose current version carries {@code ingredientKey}.
     */
    void stubContextWithIngredients(AdaptationJob job, String ingredientKey) {
      RecipeVersionDto version =
          new RecipeVersionDto(
              UUID.randomUUID(),
              UUID.randomUUID(),
              1,
              UUID.randomUUID(),
              VersionTrigger.IMPORT,
              "import",
              "embedded",
              Instant.now(),
              "system",
              UUID.randomUUID(),
              List.of(ingredient(ingredientKey)),
              List.of(),
              null,
              null,
              List.of());
      AdaptationContext context =
          new AdaptationContext(
              job.getSource().name(),
              null,
              version,
              null,
              List.of(),
              null,
              "hc:test",
              null,
              new NutritionalKnowledgeBundleDto(List.of(), List.of(), List.of(), List.of()),
              null,
              null,
              null,
              null);
      when(contextAssembler.assemble(any(), anyList(), any(TriggerInputs.class)))
          .thenReturn(context);
    }

    void stubFilterAllPass() {
      when(filter.checkRecipe(any(), any(), anyList())).thenReturn(pass());
    }

    /** Reject any candidate whose resulting key set contains {@code blockedKey}. */
    void stubFilterRejectingKey(String blockedKey) {
      when(filter.checkRecipe(any(), any(), anyList()))
          .thenAnswer(
              inv -> {
                List<String> keys = inv.getArgument(2);
                return keys.contains(blockedKey) ? failResult() : pass();
              });
    }

    void stubScoringPassthrough() {
      when(scoringEngine.selectTopN(anyList()))
          .thenAnswer(inv -> inv.getArgument(0)); // identity: keep all survivors
    }

    void stubLlmPicksFirstSurvivingCandidate() {
      when(scoringEngine.shouldAutoSkipStageC(any())).thenReturn(false);
      when(llmInvoker.invoke(any(), any(AdaptationContext.class)))
          .thenAnswer(
              inv -> {
                AdaptationContext ctx = inv.getArgument(1);
                AdaptationCandidateDto top = ctx.candidates().get(0);
                return new RecipeAdaptationResponse(
                    top.index(),
                    top.proposedClassification(),
                    "picked",
                    "",
                    BigDecimal.valueOf(0.9),
                    BigDecimal.valueOf(0.9),
                    null,
                    top.proposedDiff(),
                    List.of());
              });
    }

    AdaptationContext captureLlmContext() {
      ArgumentCaptor<AdaptationContext> ctx = ArgumentCaptor.forClass(AdaptationContext.class);
      verify(llmInvoker).invoke(any(), ctx.capture());
      return ctx.getValue();
    }

    JobFailureReason runAndCaptureFailureReason(AdaptationJob job) {
      try {
        service.processJob(job);
      } catch (RuntimeException ignored) {
        // sync callers rethrow; we assert via the published failed event.
      }
      ArgumentCaptor<AdaptationJobFailedEvent> ev =
          ArgumentCaptor.forClass(AdaptationJobFailedEvent.class);
      verify(events).publishEvent(ev.capture());
      return ev.getValue().reason();
    }

    static IngredientDto ingredient(String key) {
      return new IngredientDto(
          UUID.randomUUID(),
          0,
          key,
          key,
          BigDecimal.ONE,
          "unit",
          null,
          false,
          false,
          BigDecimal.ONE);
    }
  }
}
