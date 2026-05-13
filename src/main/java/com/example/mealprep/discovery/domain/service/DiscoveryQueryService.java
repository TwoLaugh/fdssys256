package com.example.mealprep.discovery.domain.service;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoveryScrapeLogEntryDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public read-side facade for the discovery module. Per LLD lines 358-365.
 *
 * <p>{@code getJob} returns the row without user-scoping (admin/debug); {@code getJobForUser}
 * enforces the user filter for the user-facing controller path.
 */
public interface DiscoveryQueryService {

  /** Admin/debug lookup: returns the job without filtering on {@code userId}. */
  Optional<DiscoveryJobDto> getJob(UUID jobId);

  /** User-facing lookup: empty when the id is unknown OR belongs to another user. */
  Optional<DiscoveryJobDto> getJobForUser(UUID userId, UUID jobId);

  /** Page of the user's jobs, ordered by {@code queuedAt} descending. */
  Page<DiscoveryJobDto> listJobsForUser(UUID userId, Pageable pageable);

  /**
   * Page of scrape-log rows for the given job. Pre-checks {@code existsById(jobId)} so the
   * controller returns 404 (not silent empty page) when the job is unknown.
   */
  Page<DiscoveryScrapeLogEntryDto> getScrapeLog(UUID jobId, Pageable pageable);

  /** All registered sources, ordered by {@code displayName} ASC for stable UI ordering. */
  List<DiscoverySourceDto> listSources();

  /** Lookup by stable source key. */
  Optional<DiscoverySourceDto> getSource(String sourceKey);
}
