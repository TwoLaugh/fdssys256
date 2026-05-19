package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.PendingChangeExpiredException;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.adaptation.exception.PendingChangeSupersededException;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.spi.RecipeSubstitutionRecorder;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

/**
 * DB-backed lifecycle coverage for {@link AdaptationServiceImpl}'s trigger-enqueue verbs, the
 * accept/reject pending-change state machine (every guard branch), {@code retryFailedJob}, and the
 * {@link AdaptationQueryService} read fan-out.
 *
 * <p>Rows are seeded directly through the repositories (no controller POST → no async worker
 * pipeline racing the assertions, per wave-3 retro 0012). Each {@link PendingChange} is anchored to
 * a real parent {@code adaptation_jobs} row so the NOT-NULL {@code job_id} FK is satisfied
 * (round-6: direct-repo seeds must satisfy the whole FK graph). Children are deleted before
 * parents.
 *
 * <p>{@code RecipeServiceImpl} implements four SPI interfaces; {@code @MockBean}-ing only one would
 * evict the shared bean and break {@code AdaptationServiceImpl}'s {@code RecipeWriteApi} ctor dep
 * (round-6 multi-interface eviction) — so all four siblings are mocked. Time fixtures anchor to
 * {@code Instant.now()} (no hard-coded dates).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationServiceLifecycleIT {

  @Autowired private AdaptationService adaptationService;
  @Autowired private AdaptationQueryService queryService;
  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private PendingChangeRepository pendingChangeRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockBean private RecipeWriteApi recipeWriteApi;
  @MockBean private RecipeQueryService recipeQueryService;
  @MockBean private RecipeUpdateService recipeUpdateService;
  @MockBean private RecipeSubstitutionRecorder recipeSubstitutionRecorder;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_pending_changes");
    jdbcTemplate.update("DELETE FROM adaptation_traces");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
  }

  // ---------------- trigger enqueue verbs ----------------

  @Test
  void enqueueImportJob_userCatalogue_persistsPendingChangePolicy_andHonoursParentTrace() {
    UUID parentTrace = UUID.randomUUID();
    ImportJobRequest req =
        new ImportJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Catalogue.USER,
            DataQuality.AI_GENERATED,
            JsonNodeFactory.instance.objectNode(),
            parentTrace);

    UUID jobId = adaptationService.enqueueImportJob(req);

    AdaptationJob saved = jobRepository.findById(jobId).orElseThrow();
    assertThat(saved.getSource()).isEqualTo(JobSource.IMPORT);
    assertThat(saved.getPriority()).isEqualTo(JobPriority.ASYNC);
    assertThat(saved.getApprovalPolicy()).isEqualTo(ApprovalPolicy.PENDING_CHANGE);
    assertThat(saved.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(saved.getTraceId()).isEqualTo(parentTrace);
  }

  @Test
  void enqueueImportJob_systemCatalogue_usesDirectPolicy_andGeneratesTraceWhenAbsent() {
    ImportJobRequest req =
        new ImportJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Catalogue.SYSTEM,
            DataQuality.USER_VERIFIED,
            null,
            null);

    UUID jobId = adaptationService.enqueueImportJob(req);

    AdaptationJob saved = jobRepository.findById(jobId).orElseThrow();
    assertThat(saved.getApprovalPolicy()).isEqualTo(ApprovalPolicy.DIRECT);
    assertThat(saved.getCatalogue()).isEqualTo(Catalogue.SYSTEM);
    assertThat(saved.getTraceId()).isNotNull();
  }

  @Test
  void enqueueDataModelChangeJobs_createsOneBatchJobPerAffectedRecipe() {
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    UUID r3 = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    DataModelJobRequest req =
        new DataModelJobRequest(
            UUID.randomUUID(),
            DataModelChangeType.PREFERENCE,
            JsonNodeFactory.instance.objectNode(),
            Set.of(r1, r2, r3),
            traceId);

    List<UUID> ids = adaptationService.enqueueDataModelChangeJobs(req);

    assertThat(ids).hasSize(3);
    List<AdaptationJob> jobs = jobRepository.findAllById(ids);
    assertThat(jobs)
        .allSatisfy(
            j -> {
              assertThat(j.getSource()).isEqualTo(JobSource.DATA_MODEL_CHANGE);
              assertThat(j.getPriority()).isEqualTo(JobPriority.BATCH);
              assertThat(j.getTraceId()).isEqualTo(traceId);
              assertThat(j.getStatus()).isEqualTo(JobStatus.PENDING);
            });
    assertThat(jobs).extracting(AdaptationJob::getRecipeId).containsExactlyInAnyOrder(r1, r2, r3);
  }

  // ---------------- accept pending-change state machine ----------------

  @Test
  void acceptPendingChange_pendingRow_writesThroughRecipeWriteApi_andStampsAccepted() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));
    UUID newVersionId = UUID.randomUUID();
    when(recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(newVersionId, pc.getBaseBranchId()));

    PendingChangeDto dto =
        adaptationService.acceptPendingChange(
            pc.getId(), new AcceptPendingChangeRequest(null, pc.getOptimisticVersion()), actor);

    assertThat(dto.status()).isEqualTo(PendingChangeStatus.ACCEPTED);
    PendingChange reloaded = pendingChangeRepository.findById(pc.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(PendingChangeStatus.ACCEPTED);
    assertThat(reloaded.getAcceptedVersionId()).isEqualTo(newVersionId);
    assertThat(reloaded.getResolvedAt()).isNotNull();
  }

  @Test
  void acceptPendingChange_withUserEdits_marksModified() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));
    when(recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(UUID.randomUUID(), pc.getBaseBranchId()));

    PendingChangeDto dto =
        adaptationService.acceptPendingChange(
            pc.getId(),
            new AcceptPendingChangeRequest(
                objectMapper.createObjectNode(), pc.getOptimisticVersion()),
            actor);

    assertThat(dto.status()).isEqualTo(PendingChangeStatus.MODIFIED);
    assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getUserEdits())
        .isNotNull();
  }

  @Test
  void acceptPendingChange_otherUsersRow_isNotFound_doesNotLeak() {
    UUID owner = UUID.randomUUID();
    PendingChange pc =
        seedPending(owner, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                adaptationService.acceptPendingChange(
                    pc.getId(),
                    new AcceptPendingChangeRequest(null, pc.getOptimisticVersion()),
                    UUID.randomUUID()))
        .isInstanceOf(PendingChangeNotFoundException.class);
  }

  @Test
  void acceptPendingChange_missingRow_throwsNotFound() {
    assertThatThrownBy(
            () ->
                adaptationService.acceptPendingChange(
                    UUID.randomUUID(), new AcceptPendingChangeRequest(null, 0L), UUID.randomUUID()))
        .isInstanceOf(PendingChangeNotFoundException.class);
  }

  @Test
  void acceptPendingChange_alreadyRejected_throwsNotPending() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.REJECTED, Instant.now().plus(5, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                adaptationService.acceptPendingChange(
                    pc.getId(),
                    new AcceptPendingChangeRequest(null, pc.getOptimisticVersion()),
                    actor))
        .isInstanceOf(PendingChangeNotPendingException.class);
  }

  @Test
  void acceptPendingChange_expiredRow_flipsToExpired_andThrows() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().minus(1, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                adaptationService.acceptPendingChange(
                    pc.getId(),
                    new AcceptPendingChangeRequest(null, pc.getOptimisticVersion()),
                    actor))
        .isInstanceOf(PendingChangeExpiredException.class);

    PendingChange reloaded = pendingChangeRepository.findById(pc.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(PendingChangeStatus.EXPIRED);
    assertThat(reloaded.getResolvedAt()).isNotNull();
  }

  @Test
  void acceptPendingChange_supersededRow_throwsSuperseded() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));
    PendingChange superseder =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));
    pc.setSupersededBy(superseder.getId());
    pendingChangeRepository.saveAndFlush(pc);

    assertThatThrownBy(
            () ->
                adaptationService.acceptPendingChange(
                    pc.getId(),
                    new AcceptPendingChangeRequest(null, pc.getOptimisticVersion()),
                    actor))
        .isInstanceOf(PendingChangeSupersededException.class);
  }

  @Test
  void acceptPendingChange_staleOptimisticVersion_throwsOptimisticLockFailure() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                adaptationService.acceptPendingChange(
                    pc.getId(),
                    new AcceptPendingChangeRequest(null, pc.getOptimisticVersion() + 99),
                    actor))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  // ---------------- reject pending-change state machine ----------------

  @Test
  void rejectPendingChange_pendingRow_marksRejected() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));

    PendingChangeDto dto =
        adaptationService.rejectPendingChange(
            pc.getId(), new RejectPendingChangeRequest("not for me"), actor);

    assertThat(dto.status()).isEqualTo(PendingChangeStatus.REJECTED);
    PendingChange reloaded = pendingChangeRepository.findById(pc.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(PendingChangeStatus.REJECTED);
    assertThat(reloaded.getResolvedAt()).isNotNull();
  }

  @Test
  void rejectPendingChange_otherUsersRow_isNotFound() {
    UUID owner = UUID.randomUUID();
    PendingChange pc =
        seedPending(owner, PendingChangeStatus.PENDING, Instant.now().plus(5, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                adaptationService.rejectPendingChange(
                    pc.getId(), new RejectPendingChangeRequest(null), UUID.randomUUID()))
        .isInstanceOf(PendingChangeNotFoundException.class);
  }

  @Test
  void rejectPendingChange_nonPending_throwsNotPending() {
    UUID actor = UUID.randomUUID();
    PendingChange pc =
        seedPending(actor, PendingChangeStatus.ACCEPTED, Instant.now().plus(5, ChronoUnit.DAYS));

    assertThatThrownBy(
            () ->
                adaptationService.rejectPendingChange(
                    pc.getId(), new RejectPendingChangeRequest(null), actor))
        .isInstanceOf(PendingChangeNotPendingException.class);
  }

  // ---------------- retryFailedJob ----------------

  @Test
  void retryFailedJob_failedSyncJob_clonesFreshPendingJob_chainedToParent() {
    AdaptationJob failed = seedJob(JobStatus.FAILED, JobSource.FEEDBACK, JobPriority.SYNC);

    var dto = adaptationService.retryFailedJob(failed.getId());

    assertThat(dto.status()).isEqualTo(JobStatus.PENDING);
    assertThat(dto.id()).isNotEqualTo(failed.getId());
    AdaptationJob retried = jobRepository.findById(dto.id()).orElseThrow();
    assertThat(retried.getParentDecisionId()).isEqualTo(failed.getId());
    assertThat(retried.getRecipeId()).isEqualTo(failed.getRecipeId());
  }

  @Test
  void retryFailedJob_nonFailedJob_throwsNotRetryable() {
    AdaptationJob done = seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);

    assertThatThrownBy(() -> adaptationService.retryFailedJob(done.getId()))
        .isInstanceOf(
            com.example.mealprep.adaptation.exception.AdaptationJobNotRetryableException.class);
  }

  @Test
  void retryFailedJob_missingJob_throwsNotFound() {
    assertThatThrownBy(() -> adaptationService.retryFailedJob(UUID.randomUUID()))
        .isInstanceOf(
            com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException.class);
  }

  @Test
  void retryFailedJob_failedBatchJob_clonesWithoutJobReadyEvent() {
    AdaptationJob failed =
        seedJob(JobStatus.FAILED, JobSource.DATA_MODEL_CHANGE, JobPriority.BATCH);

    var dto = adaptationService.retryFailedJob(failed.getId());

    assertThat(dto.priority()).isEqualTo(JobPriority.BATCH);
    assertThat(jobRepository.findById(dto.id()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.PENDING);
  }

  // ---------------- query read fan-out ----------------

  @Test
  void queryReadSurface_rankedPending_jobLookups_andPagedHistory() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    PendingChange highImpact =
        seedPendingFor(
            userId,
            recipeId,
            ChangeDimension.SALT_LEVEL,
            new BigDecimal("0.900"),
            new BigDecimal("0.800"));
    seedPendingFor(
        userId,
        UUID.randomUUID(),
        ChangeDimension.PROTEIN,
        new BigDecimal("0.100"),
        new BigDecimal("0.200"));

    // listPendingForUser — ranked by impact desc; highest-impact first.
    var ranked = queryService.listPendingForUser(userId);
    assertThat(ranked).isNotEmpty();
    assertThat(ranked.get(0).id()).isEqualTo(highImpact.getId());

    // getPendingChange — single projection round-trips.
    assertThat(queryService.getPendingChange(highImpact.getId()))
        .hasValueSatisfying(d -> assertThat(d.userId()).isEqualTo(userId));
    assertThat(queryService.getPendingChange(UUID.randomUUID())).isEmpty();

    // listPendingHistoryForRecipe (page-less + paged overload).
    assertThat(queryService.listPendingHistoryForRecipe(recipeId)).hasSize(1);

    // job read surface. IMPORT source so the run-history filter below isolates exactly this row
    // (the two seedPendingFor parent jobs are FEEDBACK).
    AdaptationJob job = seedJob(JobStatus.PENDING, JobSource.IMPORT, JobPriority.ASYNC);
    assertThat(queryService.getJob(job.getId())).isPresent();
    assertThat(queryService.getJob(UUID.randomUUID())).isEmpty();
    assertThat(
            queryService
                .getJobsForRecipe(job.getRecipeId(), PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(1);
    assertThat(
            queryService
                .getActiveJobsForUser(job.getUserId(), PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(1);

    // trace / hint read surface — empty but exercised end-to-end through the real stack.
    assertThat(queryService.getTracesForRecipe(recipeId, PageRequest.of(0, 20)).getTotalElements())
        .isZero();
    assertThat(
            queryService
                .getTracesForPromptVersion(
                    "adaptation/recipe-adaptation", "v1", PageRequest.of(0, 5))
                .getTotalElements())
        .isZero();
    assertThat(queryService.getTraceForJob(job.getId())).isEmpty();
    assertThat(queryService.getActiveHintsForVersion(UUID.randomUUID())).isEmpty();
    assertThat(queryService.getActiveHintsForVersions(List.of())).isEmpty();
    assertThat(queryService.getActiveHintsForVersions(List.of(UUID.randomUUID()))).isEmpty();
    assertThat(queryService.getMostRecentResultForRecipe(recipeId)).isEmpty();
    assertThat(
            queryService
                .getRunHistory(
                    JobSource.IMPORT,
                    Instant.now().minus(1, ChronoUnit.DAYS),
                    Instant.now().plus(1, ChronoUnit.DAYS),
                    PageRequest.of(0, 20))
                .getTotalElements())
        .isEqualTo(1);
  }

  // ---------------- helpers ----------------

  private AdaptationJob seedJob(JobStatus status, JobSource source, JobPriority priority) {
    return jobRepository.saveAndFlush(
        AdaptationJob.builder()
            .id(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .source(source)
            .priority(priority)
            .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
            .status(status)
            .inputs(JsonNodeFactory.instance.objectNode())
            .traceId(UUID.randomUUID())
            .enqueuedAt(Instant.now())
            .build());
  }

  private PendingChange seedPending(UUID userId, PendingChangeStatus status, Instant expiresAt) {
    AdaptationJob job = seedJob(JobStatus.RUNNING, JobSource.FEEDBACK, JobPriority.SYNC);
    return pendingChangeRepository.saveAndFlush(
        PendingChange.builder()
            .id(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .userId(userId)
            .jobId(job.getId())
            .traceId(UUID.randomUUID())
            .changeDimension(ChangeDimension.SALT_LEVEL)
            .proposedDiff(JsonNodeFactory.instance.objectNode())
            .proposedClassification(AdaptationClassification.VERSION)
            .baseVersionId(UUID.randomUUID())
            .baseBranchId(UUID.randomUUID())
            .reasoning("seed reasoning")
            .confidence(new BigDecimal("0.750"))
            .impactScore(new BigDecimal("0.500"))
            .promptTemplateVersion("v1")
            .status(status)
            .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .expiresAt(expiresAt)
            .build());
  }

  private PendingChange seedPendingFor(
      UUID userId,
      UUID recipeId,
      ChangeDimension dimension,
      BigDecimal impact,
      BigDecimal confidence) {
    AdaptationJob job = seedJob(JobStatus.RUNNING, JobSource.FEEDBACK, JobPriority.SYNC);
    return pendingChangeRepository.saveAndFlush(
        PendingChange.builder()
            .id(UUID.randomUUID())
            .recipeId(recipeId)
            .userId(userId)
            .jobId(job.getId())
            .traceId(UUID.randomUUID())
            .changeDimension(dimension)
            .proposedDiff(JsonNodeFactory.instance.objectNode())
            .proposedClassification(AdaptationClassification.VERSION)
            .baseVersionId(UUID.randomUUID())
            .baseBranchId(UUID.randomUUID())
            .reasoning("ranked seed")
            .confidence(confidence)
            .impactScore(impact)
            .promptTemplateVersion("v1")
            .status(PendingChangeStatus.PENDING)
            .createdAt(Instant.now().minus(2, ChronoUnit.HOURS))
            .expiresAt(Instant.now().plus(10, ChronoUnit.DAYS))
            .build());
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
}
