package com.example.mealprep.adaptation.domain.service;

import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read fan-out — pending-change reads, job + trace history, and planner-hint reads.
 *
 * <p>Per LLD §Service Interfaces lines 520-538; signatures verbatim from {@code
 * lld/adaptation-pipeline.md}.
 */
public interface AdaptationQueryService {

  /** Top-3 ranked PENDING for the user — applies the HLD's 3-per-week budget rule. */
  List<PendingChangeListItemDto> listPendingForUser(UUID userId);

  /** Full history of PENDING + resolved rows for a recipe, newest first. */
  List<PendingChangeListItemDto> listPendingHistoryForRecipe(UUID recipeId);

  /** Single-row read; empty when missing. */
  Optional<PendingChangeDto> getPendingChange(UUID pendingChangeId);

  /** Single-job read by id; empty when missing. Backs the admin job-by-id endpoint. */
  Optional<AdaptationJobDto> getJob(UUID jobId);

  /** Page of jobs for a recipe, newest first. */
  Page<AdaptationJobDto> getJobsForRecipe(UUID recipeId, Pageable pageable);

  /** Page of jobs for a user filtered to active statuses. */
  Page<AdaptationJobDto> getActiveJobsForUser(UUID userId, Pageable pageable);

  /** Page of traces for a recipe, newest first. */
  Page<AdaptationTraceDto> getTracesForRecipe(UUID recipeId, Pageable pageable);

  /** Page of traces filtered by prompt-template name + version (admin dashboards). */
  Page<AdaptationTraceDto> getTracesForPromptVersion(
      String name, String version, Pageable pageable);

  /** Single-trace read by job id; empty when missing. */
  Optional<AdaptationTraceDto> getTraceForJob(UUID jobId);

  /** Active (not invalidated) planner hints for a single version. */
  List<PlannerHintDto> getActiveHintsForVersion(UUID versionId);

  /** Active planner hints for a batch of versions — keyed by versionId. */
  Map<UUID, List<PlannerHintDto>> getActiveHintsForVersions(List<UUID> versionIds);

  /** Most-recent {@code DONE} result for a recipe; empty when no successful job has run. */
  Optional<AdaptationResultDto> getMostRecentResultForRecipe(UUID recipeId);

  /** Run-history feed: jobs of a source within a [from, to] enqueue window, newest first. */
  Page<AdaptationJobDto> getRunHistory(
      JobSource source, Instant from, Instant to, Pageable pageable);
}
