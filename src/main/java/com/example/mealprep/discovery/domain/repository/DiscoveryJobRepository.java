package com.example.mealprep.discovery.domain.repository;

import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DiscoveryJob}. Package-private — cross-module callers go
 * through {@code DiscoveryQueryService} / {@code DiscoveryService}. The {@code
 * DiscoveryBoundaryTest} (ArchUnit) backstops the visibility rule.
 */
public interface DiscoveryJobRepository extends JpaRepository<DiscoveryJob, UUID> {

  Optional<DiscoveryJob> findByIdAndUserId(UUID id, UUID userId);

  Page<DiscoveryJob> findByUserIdOrderByQueuedAtDesc(UUID userId, Pageable pageable);

  /**
   * Hard-delete EVERY discovery job. Used ONLY by the {@code e2e}-profile test-support cleanup
   * ({@code E2eDiscoveryResetController}) to reset the cross-scenario discovery dedup memory —
   * there is no production caller (discovery jobs are an append-only audit in prod). {@code
   * discovery_scrape_log.job_id} references {@code discovery_jobs(id)} {@code ON DELETE CASCADE},
   * so this single statement also sweeps every scrape-log row — and with it the content-fingerprint
   * dedup window the runner consults ({@code existsByContentFingerprintAndOccurredAtAfter}).
   * Without this reset, a SUCCESS scrape row from one scenario's cold-start fill makes the
   * deterministic seed recipes look like DUPLICATEs in a later scenario's cold-start, so discovery
   * ingests nothing. Returns the count of jobs deleted.
   */
  @Modifying
  @Query("delete from DiscoveryJob j")
  int deleteAllJobs();

  /**
   * Watchdog: orphan running jobs whose {@code started_at} predates the heartbeat window. The
   * orphan sweep (lands in 01d) transitions these to {@code FAILED}.
   */
  @Query("select j from DiscoveryJob j where j.status = 'RUNNING' and j.startedAt < :threshold")
  List<DiscoveryJob> findOrphanRunning(@Param("threshold") Instant threshold);

  /**
   * Native UPDATE for the QUEUED→CANCELLED cancellation flip. Bypasses Hibernate's full-entity
   * dirty-check + {@code @Version} optimistic locking, which races with the async {@code
   * DiscoveryJobRunner}'s persistence context when the runner picks the job up between the
   * controller's read and write (round-8 retro: StaleObjectStateException pattern). Bumps
   * optimisticVersion so subsequent JPA reads observe the new state cleanly.
   *
   * <p><strong>Status guard (discovery-6).</strong> The {@code WHERE ... AND j.status = 'QUEUED'}
   * clause makes the flip atomic against a concurrent runner claim: if the async runner already
   * transitioned the row QUEUED→RUNNING (via {@code DiscoveryJobTransitions.claim}) in the window
   * between the controller's read and this UPDATE, the guarded UPDATE affects 0 rows rather than
   * clobbering the now-RUNNING job back to CANCELLED. {@code cancelJob} treats {@code rows == 0} as
   * "already claimed/terminal" and falls through to the in-memory cancellation-flag path so the
   * running job still stops cleanly.
   */
  // clearAutomatically + flushAutomatically: the cancel flow loads the job via
  // findByIdAndUserId (managed, QUEUED) before this bulk UPDATE. Without clearing, the controller's
  // post-cancel re-read returns the stale first-level-cached QUEUED entity instead of the
  // freshly-UPDATEd CANCELLED row (textbook @Modifying stale-persistence-context trap).
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE DiscoveryJob j SET j.status = :status, j.completedAt = :completedAt,"
          + " j.errorSummary = :errorSummary, j.optimisticVersion = j.optimisticVersion + 1"
          + " WHERE j.id = :id AND j.status = 'QUEUED'")
  int markCancelledIfQueued(
      @Param("id") UUID id,
      @Param("status") DiscoveryJobStatus status,
      @Param("completedAt") Instant completedAt,
      @Param("errorSummary") String errorSummary);
}
