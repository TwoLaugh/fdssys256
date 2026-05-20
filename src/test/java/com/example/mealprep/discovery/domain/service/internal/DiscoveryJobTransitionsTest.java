package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoveryScrapeLogRepository;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Pure-unit coverage of {@link DiscoveryJobTransitions}. The class was previously only exercised
 * via Testcontainers ITs (not visible to Pitest); these tests pin every per-step tx helper so the
 * {@code lambda$*}, {@code Optional::ifPresent}, and {@code MathMutator} survivors die.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryJobTransitionsTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoveryScrapeLogRepository scrapeLogRepository;

  private DiscoveryJobTransitions transitions;

  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  @BeforeEach
  void setUp() {
    transitions = new DiscoveryJobTransitions(jobRepository, scrapeLogRepository);
  }

  // ---------- claim ----------

  @Test
  void claim_jobNotFound_returnsEmpty_noWrite() {
    // kills NegateConditionalsMutator on the `job == null` guard (line 54) and the
    // EmptyObjectReturnValsMutator on the empty-Optional return path.
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    Optional<DiscoveryJob> result = transitions.claim(jobId);

    assertThat(result).isEmpty();
    verify(jobRepository, never()).saveAndFlush(any());
  }

  @Test
  void claim_notQueued_returnsEmpty_noWrite() {
    // kills NegateConditionalsMutator on status-equality check (line 58) plus the
    // VoidMethodCallMutator on setStatus / setStartedAt (lines 65-66) — they must not fire here.
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.RUNNING);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    Optional<DiscoveryJob> result = transitions.claim(jobId);

    assertThat(result).isEmpty();
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.RUNNING);
    assertThat(job.getStartedAt()).isNull();
    verify(jobRepository, never()).saveAndFlush(any());
  }

  @Test
  void claim_queuedJob_flipsToRunning_stampsStartedAt_returnsSaved() {
    // kills VoidMethodCallMutator on setStatus / setStartedAt (lines 65, 66) — without these
    // calls the saved job would stay QUEUED with null startedAt. Also kills the
    // EmptyObjectReturnValsMutator at line 68: the result must carry the saved job, not empty.
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.QUEUED);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(jobRepository.saveAndFlush(job)).thenReturn(job);

    Instant before = Instant.now().minusSeconds(1);
    Optional<DiscoveryJob> result = transitions.claim(jobId);
    Instant after = Instant.now().plusSeconds(1);

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(DiscoveryJobStatus.RUNNING);
    assertThat(result.get().getStartedAt()).isBetween(before, after);
    verify(jobRepository, times(1)).saveAndFlush(job);
  }

  @Test
  void claim_optimisticLockingFailure_returnsEmpty() {
    // kills the catch-branch NullReturnValsMutator: the returned Optional must be empty, not null.
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.QUEUED);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(jobRepository.saveAndFlush(job))
        .thenThrow(new OptimisticLockingFailureException("claim race"));

    Optional<DiscoveryJob> result = transitions.claim(jobId);

    assertThat(result).isEmpty();
  }

  // ---------- writeScrapeRow ----------

  @Test
  void writeScrapeRow_delegatesToScrapeLogRepository() {
    DiscoveryScrapeLog row = DiscoveryTestData.sampleScrapeLog(UUID.randomUUID());

    transitions.writeScrapeRow(row);

    verify(scrapeLogRepository, times(1)).save(row);
  }

  // ---------- scrapeLogExistsSince ----------

  @Test
  void scrapeLogExistsSince_returnsTrueWhenRepoTrue() {
    // kills BooleanFalseReturnValsMutator (line 88): mutating the return to false would break this.
    Instant cutoff = Instant.parse("2026-05-01T00:00:00Z");
    when(scrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter("abc", cutoff))
        .thenReturn(true);

    assertThat(transitions.scrapeLogExistsSince("abc", cutoff)).isTrue();
  }

  @Test
  void scrapeLogExistsSince_returnsFalseWhenRepoFalse() {
    // kills BooleanTrueReturnValsMutator (line 88): mutating to true would break this.
    Instant cutoff = Instant.parse("2026-05-01T00:00:00Z");
    when(scrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter("abc", cutoff))
        .thenReturn(false);

    assertThat(transitions.scrapeLogExistsSince("abc", cutoff)).isFalse();
  }

  // ---------- recordCandidatesSeen ----------

  @Test
  void recordCandidatesSeen_jobPresent_setsCountAndSaves() {
    // kills VoidMethodCallMutator on ifPresent (line 99) and lambda setCandidatesSeen (line 101).
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setCandidatesSeen(0);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    transitions.recordCandidatesSeen(jobId, 42);

    assertThat(job.getCandidatesSeen()).isEqualTo(42);
    verify(jobRepository, times(1)).save(job);
  }

  @Test
  void recordCandidatesSeen_jobMissing_noSave() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    transitions.recordCandidatesSeen(jobId, 42);

    verify(jobRepository, never()).save(any());
  }

  // ---------- recordCandidatesAfterFilter ----------

  @Test
  void recordCandidatesAfterFilter_jobPresent_setsCountAndSaves() {
    // kills VoidMethodCallMutator on ifPresent (line 111) and setCandidatesAfterFilter (line 113).
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setCandidatesAfterFilter(0);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    transitions.recordCandidatesAfterFilter(jobId, 17);

    assertThat(job.getCandidatesAfterFilter()).isEqualTo(17);
    verify(jobRepository, times(1)).save(job);
  }

  // ---------- incrementIngested ----------

  @Test
  void incrementIngested_addsOneAndSaves() {
    // kills MathMutator (line 129: `+ 1` → `- 1`) and the VoidMethodCallMutator on
    // setRecipesIngested
    // plus the ifPresent VoidMethodCallMutator (line 127).
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRecipesIngested(3);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    transitions.incrementIngested(jobId);

    assertThat(job.getRecipesIngested()).isEqualTo(4);
    verify(jobRepository, times(1)).save(job);
  }

  @Test
  void incrementIngested_jobMissing_noSave() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    transitions.incrementIngested(jobId);

    verify(jobRepository, never()).save(any());
  }

  // ---------- incrementSkippedDuplicate ----------

  @Test
  void incrementSkippedDuplicate_addsOneAndSaves() {
    // kills MathMutator (line 141) and setRecipesSkippedDuplicate VoidMethodCallMutator +
    // ifPresent.
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setRecipesSkippedDuplicate(7);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

    transitions.incrementSkippedDuplicate(jobId);

    assertThat(job.getRecipesSkippedDuplicate()).isEqualTo(8);
    verify(jobRepository, times(1)).save(job);
  }

  // ---------- finaliseTo ----------

  @Test
  void finaliseTo_jobPresent_setsAllTerminalFields() {
    // kills five VoidMethodCallMutator survivors on lambda$finaliseTo$4 (lines 162-166) plus the
    // NullReturnValsMutator at line 167 (the lambda must return the saved job, not null) plus the
    // EmptyObjectReturnValsMutator at line 158 (must return populated Optional, not
    // Optional.empty).
    UUID jobId = UUID.randomUUID();
    DiscoveryJob job = DiscoveryTestData.sampleJob(USER_ID);
    job.setId(jobId);
    job.setStatus(DiscoveryJobStatus.RUNNING);
    when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
    when(jobRepository.saveAndFlush(job)).thenReturn(job);

    Instant before = Instant.now().minusSeconds(1);
    Optional<DiscoveryJob> result =
        transitions.finaliseTo(
            jobId,
            DiscoveryJobStatus.SUCCEEDED,
            "all good",
            List.of("src_a", "src_b"),
            List.of("src_c"));
    Instant after = Instant.now().plusSeconds(1);

    assertThat(result).isPresent();
    assertThat(result.get()).isSameAs(job);
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.SUCCEEDED);
    assertThat(job.getCompletedAt()).isBetween(before, after);
    assertThat(job.getErrorSummary()).isEqualTo("all good");
    assertThat(job.getSourcesSucceeded()).containsExactly("src_a", "src_b");
    assertThat(job.getSourcesFailed()).containsExactly("src_c");
    verify(jobRepository, times(1)).saveAndFlush(job);
  }

  @Test
  void finaliseTo_jobMissing_returnsEmpty_noWrite() {
    UUID jobId = UUID.randomUUID();
    when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

    Optional<DiscoveryJob> result =
        transitions.finaliseTo(
            jobId, DiscoveryJobStatus.FAILED, "lost", List.of(), List.of("src_a"));

    assertThat(result).isEmpty();
    verify(jobRepository, never()).saveAndFlush(any());
  }
}
