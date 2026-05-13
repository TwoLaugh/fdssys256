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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DiscoveryJob}. Package-private — cross-module callers go
 * through {@code DiscoveryQueryService} / {@code DiscoveryService}. The {@code
 * DiscoveryBoundaryTest} (ArchUnit) backstops the visibility rule.
 */
interface DiscoveryJobRepository extends JpaRepository<DiscoveryJob, UUID> {

  Optional<DiscoveryJob> findByIdAndUserId(UUID id, UUID userId);

  Page<DiscoveryJob> findByUserIdOrderByQueuedAtDesc(UUID userId, Pageable pageable);

  List<DiscoveryJob> findByStatus(DiscoveryJobStatus status);

  /**
   * Watchdog: orphan running jobs whose {@code started_at} predates the heartbeat window. The
   * orphan sweep (lands in 01d) transitions these to {@code FAILED}.
   */
  @Query("select j from DiscoveryJob j where j.status = 'RUNNING' and j.startedAt < :threshold")
  List<DiscoveryJob> findOrphanRunning(@Param("threshold") Instant threshold);
}
