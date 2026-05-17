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

  List<DiscoveryJob> findByStatus(DiscoveryJobStatus status);

  /**
   * Watchdog: orphan running jobs whose {@code started_at} predates the heartbeat window. The
   * orphan sweep (lands in 01d) transitions these to {@code FAILED}.
   */
  @Query("select j from DiscoveryJob j where j.status = 'RUNNING' and j.startedAt < :threshold")
  List<DiscoveryJob> findOrphanRunning(@Param("threshold") Instant threshold);

  /**
   * Native UPDATE for the QUEUED→FAILED cancellation flip. Bypasses Hibernate's full-entity
   * dirty-check + {@code @Version} optimistic locking, which races with the async {@code
   * DiscoveryJobRunner}'s persistence context when the runner picks the job up between the
   * controller's read and write (round-8 retro: StaleObjectStateException pattern). Bumps
   * optimisticVersion so subsequent JPA reads observe the new state cleanly.
   */
  @Modifying
  @Query(
      "UPDATE DiscoveryJob j SET j.status = :status, j.completedAt = :completedAt,"
          + " j.errorSummary = :errorSummary, j.optimisticVersion = j.optimisticVersion + 1"
          + " WHERE j.id = :id")
  int markCancelled(
      @Param("id") UUID id,
      @Param("status") DiscoveryJobStatus status,
      @Param("completedAt") Instant completedAt,
      @Param("errorSummary") String errorSummary);
}
