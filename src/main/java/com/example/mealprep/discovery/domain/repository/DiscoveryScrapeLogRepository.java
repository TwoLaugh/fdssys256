package com.example.mealprep.discovery.domain.repository;

import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DiscoveryScrapeLog}. Package-private — cross-module callers go
 * through {@code DiscoveryQueryService}.
 */
public interface DiscoveryScrapeLogRepository extends JpaRepository<DiscoveryScrapeLog, UUID> {

  Page<DiscoveryScrapeLog> findByJobIdOrderByOccurredAt(UUID jobId, Pageable pageable);

  List<DiscoveryScrapeLog> findByJobId(UUID jobId);

  boolean existsByContentFingerprint(String fingerprint);

  /**
   * Lookback-window fingerprint dedup query used by the 01d runner — short-circuits on a
   * fingerprint already seen since the cutoff. Backed by the partial fingerprint index from 01a.
   */
  boolean existsByContentFingerprintAndOccurredAtAfter(String contentFingerprint, Instant cutoff);
}
