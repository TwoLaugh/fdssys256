package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link AdaptationJob}. {@code public} so the in-module {@code
 * domain.service} package can inject it (mirrors the pattern used by {@code RecipeRepository} /
 * {@code InventoryItemRepository}); cross-module isolation comes from the (01f-shipping) {@code
 * ModuleBoundaryArchTest}. Verbatim from {@code lld/adaptation-pipeline.md} lines 417-430.
 */
public interface AdaptationJobRepository extends JpaRepository<AdaptationJob, UUID> {

  Optional<AdaptationJob> findByIdAndStatusIn(UUID id, Collection<JobStatus> statuses);

  /**
   * Worker scan in source-priority order: SYNC first, then ASYNC, then BATCH, breaking ties by
   * {@code enqueuedAt} ascending.
   */
  @Query(
      """
      select j from AdaptationJob j where j.status = com.example.mealprep.adaptation.domain.enums.JobStatus.PENDING
       order by case j.priority
                  when com.example.mealprep.adaptation.domain.enums.JobPriority.SYNC then 0
                  when com.example.mealprep.adaptation.domain.enums.JobPriority.ASYNC then 1
                  else 2
                end, j.enqueuedAt""")
  List<AdaptationJob> findNextPendingJobs(Pageable pageable);

  Page<AdaptationJob> findByRecipeIdOrderByEnqueuedAtDesc(UUID recipeId, Pageable p);

  Page<AdaptationJob> findByUserIdAndStatusOrderByEnqueuedAtDesc(
      UUID userId, JobStatus s, Pageable p);

  @Query(
      """
      select j from AdaptationJob j where j.recipeId = :rid
       and j.status in (com.example.mealprep.adaptation.domain.enums.JobStatus.PENDING,
                        com.example.mealprep.adaptation.domain.enums.JobStatus.RUNNING)""")
  List<AdaptationJob> findActiveByRecipeId(@Param("rid") UUID recipeId);

  /** Active-jobs read for a user filtered to a status set (PENDING + RUNNING), newest first. */
  Page<AdaptationJob> findByUserIdAndStatusInOrderByEnqueuedAtDesc(
      UUID userId, Set<JobStatus> statuses, Pageable p);

  /** Run-history feed: jobs of a source within a [from, to] enqueue window, newest first. */
  Page<AdaptationJob> findBySourceAndEnqueuedAtBetweenOrderByEnqueuedAtDesc(
      JobSource source, Instant from, Instant to, Pageable p);

  /**
   * Most-recent {@code DONE} job for a recipe — backs {@code getMostRecentResultForRecipe}. Ordered
   * by {@code completedAt} desc (falling back to {@code enqueuedAt}); first row wins.
   */
  @Query(
      """
      select j from AdaptationJob j where j.recipeId = :rid
       and j.status = com.example.mealprep.adaptation.domain.enums.JobStatus.DONE
       order by j.completedAt desc nulls last, j.enqueuedAt desc""")
  List<AdaptationJob> findMostRecentDoneForRecipe(@Param("rid") UUID recipeId, Pageable pageable);
}
