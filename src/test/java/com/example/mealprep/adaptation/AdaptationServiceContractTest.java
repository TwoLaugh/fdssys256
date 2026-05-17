package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Reflection-based contract enforcement for {@link AdaptationService}, {@link
 * AdaptationQueryService}, and {@link NutritionalKnowledgeService}. Asserts every LLD-listed method
 * exists with the right signature. Sibling Wave-3 modules (planner, feedback) hard-block on these
 * signatures — accidental drift breaks parallel agents downstream.
 *
 * <p>Per ticket-01b §Acceptance / DoD ("signatures match LLD verbatim").
 */
class AdaptationServiceContractTest {

  @Test
  void adaptation_service_has_all_nine_methods() throws NoSuchMethodException {
    Method enqueueImportJob =
        AdaptationService.class.getMethod("enqueueImportJob", ImportJobRequest.class);
    assertThat(enqueueImportJob.getReturnType()).isEqualTo(UUID.class);

    Method enqueueFeedbackJob =
        AdaptationService.class.getMethod("enqueueFeedbackJob", FeedbackJobRequest.class);
    assertThat(enqueueFeedbackJob.getReturnType()).isEqualTo(AdaptationResultDto.class);

    Method enqueueDataModelChangeJobs =
        AdaptationService.class.getMethod("enqueueDataModelChangeJobs", DataModelJobRequest.class);
    assertThat(enqueueDataModelChangeJobs.getReturnType()).isEqualTo(List.class);

    Method runPlanTimeRefineJob =
        AdaptationService.class.getMethod(
            "runPlanTimeRefineJob", PlanTimeRefineDirectiveRequest.class);
    assertThat(runPlanTimeRefineJob.getReturnType()).isEqualTo(AdaptationResultDto.class);

    Method acceptPendingChange =
        AdaptationService.class.getMethod(
            "acceptPendingChange", UUID.class, AcceptPendingChangeRequest.class, UUID.class);
    assertThat(acceptPendingChange.getReturnType()).isEqualTo(PendingChangeDto.class);

    Method rejectPendingChange =
        AdaptationService.class.getMethod(
            "rejectPendingChange", UUID.class, RejectPendingChangeRequest.class, UUID.class);
    assertThat(rejectPendingChange.getReturnType()).isEqualTo(PendingChangeDto.class);

    Method emitPlannerHint =
        AdaptationService.class.getMethod("emitPlannerHint", PlannerHintRequest.class, UUID.class);
    assertThat(emitPlannerHint.getReturnType()).isEqualTo(PlannerHintDto.class);

    Method sweepExpiredPendingChanges =
        AdaptationService.class.getMethod("sweepExpiredPendingChanges");
    assertThat(sweepExpiredPendingChanges.getReturnType()).isEqualTo(int.class);

    // 01f adds the admin retry-failed-job verb.
    Method retryFailedJob = AdaptationService.class.getMethod("retryFailedJob", UUID.class);
    assertThat(retryFailedJob.getReturnType())
        .isEqualTo(com.example.mealprep.adaptation.api.dto.AdaptationJobDto.class);

    // Sanity: the interface declares exactly the nine verbs above (no accidental extras).
    long declared = AdaptationService.class.getDeclaredMethods().length;
    assertThat(declared).isEqualTo(9);
  }

  @Test
  void adaptation_query_service_has_all_twelve_methods() throws NoSuchMethodException {
    assertThat(
            AdaptationQueryService.class
                .getMethod("listPendingForUser", UUID.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            AdaptationQueryService.class
                .getMethod("listPendingHistoryForRecipe", UUID.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            AdaptationQueryService.class.getMethod("getPendingChange", UUID.class).getReturnType())
        .isEqualTo(Optional.class);

    assertThat(
            AdaptationQueryService.class
                .getMethod("getJobsForRecipe", UUID.class, Pageable.class)
                .getReturnType())
        .isEqualTo(Page.class);
    assertThat(
            AdaptationQueryService.class
                .getMethod("getActiveJobsForUser", UUID.class, Pageable.class)
                .getReturnType())
        .isEqualTo(Page.class);

    assertThat(
            AdaptationQueryService.class
                .getMethod("getTracesForRecipe", UUID.class, Pageable.class)
                .getReturnType())
        .isEqualTo(Page.class);
    assertThat(
            AdaptationQueryService.class
                .getMethod("getTracesForPromptVersion", String.class, String.class, Pageable.class)
                .getReturnType())
        .isEqualTo(Page.class);
    assertThat(AdaptationQueryService.class.getMethod("getTraceForJob", UUID.class).getReturnType())
        .isEqualTo(Optional.class);

    assertThat(
            AdaptationQueryService.class
                .getMethod("getActiveHintsForVersion", UUID.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            AdaptationQueryService.class
                .getMethod("getActiveHintsForVersions", List.class)
                .getReturnType())
        .isEqualTo(Map.class);

    assertThat(
            AdaptationQueryService.class
                .getMethod("getMostRecentResultForRecipe", UUID.class)
                .getReturnType())
        .isEqualTo(Optional.class);

    // 01f adds the admin single-job read + the run-history (source + window) feed.
    assertThat(AdaptationQueryService.class.getMethod("getJob", UUID.class).getReturnType())
        .isEqualTo(Optional.class);
    assertThat(
            AdaptationQueryService.class
                .getMethod(
                    "getRunHistory",
                    com.example.mealprep.adaptation.domain.enums.JobSource.class,
                    java.time.Instant.class,
                    java.time.Instant.class,
                    Pageable.class)
                .getReturnType())
        .isEqualTo(Page.class);

    long declared = AdaptationQueryService.class.getDeclaredMethods().length;
    assertThat(declared).isEqualTo(13);
  }

  /**
   * Ensures the query interface lists the eleven verbs in the LLD's order — drift here would
   * surface as a sibling-ticket integration test failing on "method count mismatch" rather than
   * "missing method". We assert exactly the LLD-listed names exist (a typo'd method survives the
   * count check above).
   */
  @Test
  void adaptation_query_service_method_names_match_lld() {
    var names =
        java.util.Arrays.stream(AdaptationQueryService.class.getDeclaredMethods())
            .map(Method::getName)
            .toList();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "listPendingForUser",
            "listPendingHistoryForRecipe",
            "getPendingChange",
            "getJobsForRecipe",
            "getActiveJobsForUser",
            "getTracesForRecipe",
            "getTracesForPromptVersion",
            "getTraceForJob",
            "getActiveHintsForVersion",
            "getActiveHintsForVersions",
            "getMostRecentResultForRecipe",
            "getJob",
            "getRunHistory");
  }

  @Test
  void nutritional_knowledge_service_has_five_methods() throws NoSuchMethodException {
    assertThat(
            NutritionalKnowledgeService.class
                .getMethod("lookupPairings", List.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            NutritionalKnowledgeService.class
                .getMethod("lookupMethodEffects", String.class, List.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            NutritionalKnowledgeService.class
                .getMethod("lookupPrepRequirements", List.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            NutritionalKnowledgeService.class
                .getMethod("lookupConflicts", List.class)
                .getReturnType())
        .isEqualTo(List.class);
    assertThat(
            NutritionalKnowledgeService.class
                .getMethod("lookupForRecipe", UUID.class, List.class)
                .getReturnType()
                .getSimpleName())
        .isEqualTo("NutritionalKnowledgeBundleDto");

    long declared = NutritionalKnowledgeService.class.getDeclaredMethods().length;
    assertThat(declared).isEqualTo(5);
  }

  /** Sanity smoke for type DTOs — the DTOs the sibling tickets import compile cleanly. */
  @Test
  void public_dtos_resolve() {
    // Touch every type that sibling tickets will import; compilation is the assertion.
    assertThat(AdaptationResultDto.class.getDeclaredFields()).isNotEmpty();
    assertThat(AdaptationJobDto.class.getDeclaredFields()).isNotEmpty();
    // Count only real declared fields. Under Pitest/JaCoCo the class is instrumented with
    // synthetic probe fields ($jacocoData, $$$pitXXX), so a raw getDeclaredFields().hasSize(8)
    // fails the Pitest baseline green-suite check even though the DTO genuinely has 8 fields.
    assertThat(realFieldCount(PendingChangeListItemDto.class)).isEqualTo(8);
    assertThat(AdaptationTraceDto.class.getDeclaredFields()).isNotEmpty();
  }

  /**
   * Declared-field count excluding coverage/mutation instrumentation. Pitest and JaCoCo add
   * synthetic probe fields (names starting with {@code $}); a raw {@code getDeclaredFields()} count
   * is non-deterministic between a plain surefire run and the Pitest baseline run.
   */
  private static long realFieldCount(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(f -> !f.isSynthetic())
        .filter(f -> !f.getName().startsWith("$"))
        .map(Field::getName)
        .count();
  }
}
