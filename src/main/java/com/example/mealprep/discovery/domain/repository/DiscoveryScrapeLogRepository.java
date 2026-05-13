package com.example.mealprep.discovery.domain.repository;

import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
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
}
