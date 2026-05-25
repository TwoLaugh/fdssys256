package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.Recipe;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Recipe}. {@code public} so the in-module {@code
 * domain.service.internal} package can inject it; cross-module isolation comes from {@code
 * RecipeBoundaryTest} (ArchUnit).
 */
public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

  /** Soft-delete-aware lookup. {@code GET /api/v1/recipes/{recipeId}} routes through this. */
  Optional<Recipe> findByIdAndDeletedAtIsNull(UUID id);

  /**
   * {@code SELECT ... FOR UPDATE} on the recipe row — used by the adaptation-pipeline write path in
   * {@link com.example.mealprep.recipe.spi.RecipeWriteApi#saveAdaptedVersion} to serialise
   * concurrent head-bumps. Per LLD line 786.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Recipe r where r.id = :id and r.deletedAt is null")
  Optional<Recipe> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Recipe-01g {@code ArchiveEligibilityScanner} eligibility query. Returns SYSTEM-catalogue,
   * un-archived, un-deleted recipe IDs whose {@code last_used_in_plan_at} is null or older than the
   * supplied cutoff. Ordered oldest-first so the longest-untouched rows get archived first; bounded
   * by the {@link Pageable} (the scanner passes {@code PageRequest.of(0, 1000)}). Per LLD lines
   * 466-471.
   */
  @Query(
      """
      select r.id from Recipe r
      where r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.SYSTEM
        and r.archivedAt is null
        and r.deletedAt is null
        and (r.lastUsedInPlanAt is null or r.lastUsedInPlanAt < :cutoff)
      order by r.lastUsedInPlanAt asc nulls first
      """)
  List<UUID> findArchiveEligibleSystemRecipes(@Param("cutoff") Instant cutoff, Pageable page);

  /**
   * Bulk-archive helper used by {@code ArchiveEligibilityScanner}. Sets {@code archived_at} only on
   * rows that are not already archived (belt-and-braces race guard). Bypasses the {@code @Version}
   * check intentionally — SYSTEM-catalogue rows have no concurrent user writes. Returns the count
   * of rows updated.
   */
  @Modifying
  @Query("update Recipe r set r.archivedAt = :now" + " where r.id in :ids and r.archivedAt is null")
  int markArchived(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

  /**
   * Bulk-update {@code last_used_in_plan_at = :now} for the supplied IDs. Recipe-01g exposes this
   * via {@link
   * com.example.mealprep.recipe.domain.service.RecipeUpdateService#markUsedInPlan(java.util.List)}
   * for the cook listener + future planner. Returns the count of rows updated.
   */
  @Modifying
  @Query("update Recipe r set r.lastUsedInPlanAt = :now where r.id in :ids")
  int touchLastUsedInPlan(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

  /**
   * Planner pre-filter query — the un-archived, un-deleted recipes the planner may schedule for
   * {@code userId}. Scope is the caller's own {@code USER} catalogue rows <b>plus</b> the global
   * {@code SYSTEM} catalogue (SYSTEM rows have no owning user, so they are visible to everyone; per
   * {@code RecipeServiceImpl} they carry the nil-UUID sentinel user-id). Ordered by {@code
   * createdAt} ascending for a stable candidate order and bounded by the supplied {@link Pageable}
   * (the planner pool source passes {@code PageRequest.of(0, limit)}).
   *
   * <p>Deliberately narrow: kind / time-budget filtering happens downstream in the planner's {@code
   * HardFilterRunner} (the full filterable catalogue index is unspecified — recipe.md G6/G7 — so we
   * do not build a broad filter surface here, just enough to feed planning). Per planner.md
   * §BeamSearchEngine Stage A ("ask {@code RecipeQueryService.search(...)} for recipes matching the
   * slot kind + time budget").
   */
  @Query(
      """
      select r from Recipe r
      where r.archivedAt is null
        and r.deletedAt is null
        and (
          r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.SYSTEM
          or (r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.USER
              and r.userId = :userId)
        )
      order by r.createdAt asc
      """)
  List<Recipe> findPlannableForUser(@Param("userId") UUID userId, Pageable page);
}
