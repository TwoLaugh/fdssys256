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

  /**
   * Library list/search page ({@code GET /api/v1/recipes}). One query serves all three LLD views
   * via the caller-computed catalogue booleans:
   *
   * <ul>
   *   <li><b>Visibility</b>: {@code USER} rows only when owned by {@code :userId} (caller-private);
   *       {@code SYSTEM} rows are shared with every authenticated caller. {@code includeUser} /
   *       {@code includeSystem} encode the optional {@code catalogue} filter (absent → both true)
   *       without binding a nullable enum parameter.
   *   <li><b>State</b>: soft-deleted rows are excluded unconditionally; archived rows only when
   *       {@code includeArchived}.
   *   <li><b>Current-version join</b>: the version join pins {@code m} to the <i>current</i>
   *       version's metadata (same {@code (recipe, currentBranchId, currentVersion)} triple the
   *       affected-set queries use) so cuisine / total-time filter against what the card renders.
   *       Metadata is left-joined — a metadata-less current version still lists when neither
   *       metadata filter is set.
   *   <li><b>Optional scalars</b>: {@code namePattern} (pre-lowercased, {@code %}-wrapped, {@code
   *       '!'}-escaped by the service), {@code cuisine} and {@code maxTotalTimeMins} use the proven
   *       {@code cast(:param ...) is null or ...} idiom (see {@code NotificationRepository}).
   *   <li><b>Quality floor</b>: {@code :qualities} is the non-empty tier set {@code
   *       DataQualityGate.atOrAbove} expands from {@code minDataQuality}.
   * </ul>
   *
   * <p>Sort is pinned {@code updatedAt DESC} (id DESC tie-break for stable pages); callers pass an
   * <b>unsorted</b> {@link Pageable}. The joins are 1:1 (unique {@code (recipe, branch,
   * versionNumber)} + one metadata row per version) so {@code count(r)} needs no distinct.
   */
  @Query(
      value =
          """
          select r from Recipe r
            join com.example.mealprep.recipe.domain.entity.RecipeVersion v
              on v.recipe.id = r.id
             and v.branch.id = r.currentBranchId
             and v.versionNumber = r.currentVersion
            left join com.example.mealprep.recipe.domain.entity.RecipeMetadata m
              on m.version.id = v.id
           where r.deletedAt is null
             and (:includeArchived = true or r.archivedAt is null)
             and (
               (:includeSystem = true
                   and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.SYSTEM)
               or (:includeUser = true
                   and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.USER
                   and r.userId = :userId)
             )
             and (cast(:namePattern as string) is null or lower(r.name) like :namePattern escape '!')
             and (cast(:cuisine as string) is null or m.cuisine = :cuisine)
             and (cast(:maxTotalTimeMins as integer) is null or m.totalTimeMins <= :maxTotalTimeMins)
             and r.dataQuality in :qualities
           order by r.updatedAt desc, r.id desc
          """,
      countQuery =
          """
          select count(r) from Recipe r
            join com.example.mealprep.recipe.domain.entity.RecipeVersion v
              on v.recipe.id = r.id
             and v.branch.id = r.currentBranchId
             and v.versionNumber = r.currentVersion
            left join com.example.mealprep.recipe.domain.entity.RecipeMetadata m
              on m.version.id = v.id
           where r.deletedAt is null
             and (:includeArchived = true or r.archivedAt is null)
             and (
               (:includeSystem = true
                   and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.SYSTEM)
               or (:includeUser = true
                   and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.USER
                   and r.userId = :userId)
             )
             and (cast(:namePattern as string) is null or lower(r.name) like :namePattern escape '!')
             and (cast(:cuisine as string) is null or m.cuisine = :cuisine)
             and (cast(:maxTotalTimeMins as integer) is null or m.totalTimeMins <= :maxTotalTimeMins)
             and r.dataQuality in :qualities
          """)
  org.springframework.data.domain.Page<Recipe> searchLibrary(
      @Param("userId") UUID userId,
      @Param("includeUser") boolean includeUser,
      @Param("includeSystem") boolean includeSystem,
      @Param("includeArchived") boolean includeArchived,
      @Param("namePattern") String namePattern,
      @Param("cuisine") String cuisine,
      @Param("maxTotalTimeMins") Integer maxTotalTimeMins,
      @Param("qualities")
          Collection<com.example.mealprep.recipe.domain.entity.DataQuality> qualities,
      Pageable pageable);

  /**
   * Count of SYSTEM-catalogue recipe rows (any state — archived/deleted included). E2E test-support
   * uses this to assert the global SYSTEM catalogue is empty between scenarios (see {@code
   * E2eRecipeCatalogueController}); it has no production caller. Accessible only within the recipe
   * module per {@code RecipeBoundaryTest}.
   */
  long countByCatalogue(com.example.mealprep.recipe.domain.entity.Catalogue catalogue);

  /**
   * List every recipe in a catalogue (any state). E2E test-support only ({@code
   * E2eNutritionSeedController} iterates the SYSTEM pool to seed per-serving nutrition); no
   * production caller. Accessible only within the recipe module per {@code RecipeBoundaryTest}.
   */
  List<Recipe> findByCatalogue(com.example.mealprep.recipe.domain.entity.Catalogue catalogue);

  /**
   * Hard-delete EVERY SYSTEM-catalogue recipe row. Used ONLY by the {@code e2e}-profile
   * test-support cleanup ({@code E2eRecipeCatalogueController}) to reset the global, cross-scenario
   * SYSTEM catalogue that the cold-start discovery fill populates — there is no production caller
   * (SYSTEM rows are never bulk-purged in prod; they are archived via {@code
   * ArchiveEligibilityScanner}).
   *
   * <p>FK-safe: every recipe child table ({@code recipe_versions}, {@code recipe_branches}, {@code
   * recipe_ingredients}, {@code recipe_method_steps}, {@code recipe_metadata}, {@code recipe_tags},
   * {@code recipe_imports}, {@code recipe_substitutions}, {@code recipe_ratings}) references {@code
   * recipe_recipes(id)} (directly or via {@code recipe_versions}/{@code recipe_branches}) with
   * {@code ON DELETE CASCADE}, so this single statement sweeps the whole aggregate. Cross-module
   * references (e.g. {@code planner_scheduled_recipes.recipe_id}) are soft refs with no DB FK, so
   * they are not affected. Returns the count of recipe roots deleted.
   */
  @Modifying
  @Query(
      "delete from Recipe r"
          + " where r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.SYSTEM")
  int deleteAllSystemCatalogue();

  /**
   * Affected-set read for the adaptation module (Trigger 3). Returns one {@code (recipeId,
   * ingredientMappingKey)} row per ingredient on the <b>current version</b> of each <b>active</b>
   * {@code USER}-catalogue recipe owned by {@code userId}. The service assembles these into a
   * {@code Map<UUID, List<String>>}.
   *
   * <p>Active = {@code catalogue = USER AND userId = :userId AND archivedAt IS NULL AND deletedAt
   * IS NULL}. The current version is joined via {@code (v.recipe.id = r.id AND v.branch.id =
   * r.currentBranchId AND v.versionNumber = r.currentVersion)}; the {@code uq_recipe_versions}
   * unique constraint backs that lookup. Ingredients are joined by {@code i.version.id = v.id}
   * (backed by {@code idx_recipe_ingredients_version}). The {@link RecipeIngredient}→version join
   * is an <b>inner</b> join, so a recipe with no ingredients on its current version contributes no
   * rows (and is therefore absent from the assembled map — intentional per ticket). Single query,
   * no N+1.
   */
  @Query(
      """
      select r.id, i.ingredientMappingKey
        from Recipe r
        join com.example.mealprep.recipe.domain.entity.RecipeVersion v
          on v.recipe.id = r.id
         and v.branch.id = r.currentBranchId
         and v.versionNumber = r.currentVersion
        join com.example.mealprep.recipe.domain.entity.RecipeIngredient i
          on i.version.id = v.id
       where r.userId = :userId
         and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.USER
         and r.archivedAt is null
         and r.deletedAt is null
      """)
  List<Object[]> findCurrentVersionIngredientKeysForUser(@Param("userId") UUID userId);

  /**
   * Affected-set read for the adaptation module (Trigger 3). Returns one {@code (recipeId,
   * nutritionPerServing)} row per <b>active</b> {@code USER}-catalogue recipe owned by {@code
   * userId} whose <b>current version</b> has a non-null {@code nutrition_per_serving} jsonb. The
   * service assembles these into a {@code Map<UUID, JsonNode>}.
   *
   * <p>Same active + current-version scoping as {@link
   * #findCurrentVersionIngredientKeysForUser(UUID)}. The {@code nutrition_per_serving} jsonb is
   * mapped to {@link com.fasterxml.jackson.databind.JsonNode} on the version entity (hypersistence
   * {@code JsonBinaryType}), so it projects through JPQL verbatim — the recipe module does not
   * parse it. {@code v.nutritionPerServing is not null} prunes versions with no nutrition. Single
   * query, no N+1.
   */
  @Query(
      """
      select r.id, v.nutritionPerServing
        from Recipe r
        join com.example.mealprep.recipe.domain.entity.RecipeVersion v
          on v.recipe.id = r.id
         and v.branch.id = r.currentBranchId
         and v.versionNumber = r.currentVersion
       where r.userId = :userId
         and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.USER
         and r.archivedAt is null
         and r.deletedAt is null
         and v.nutritionPerServing is not null
      """)
  List<Object[]> findCurrentVersionNutritionForUser(@Param("userId") UUID userId);

  /**
   * Deduplication probe (recipe-2 / LLD §Flow 1 + Flow 2 — {@code DeduplicationFingerprintHasher}).
   * For each <b>active USER-catalogue</b> recipe owned by {@code userId}, returns one {@code
   * (recipeId, ingredientMappingKey)} row per ingredient on that recipe's <b>current</b> version,
   * plus the current version's total method-step count {@code (recipeId, mappingKey, methodSteps)}.
   *
   * <p>The method-step count is a correlated subquery so a recipe with zero ingredients (which the
   * inner ingredient join would drop) is irrelevant to dedup — a recipe with no ingredients cannot
   * collide on an ingredient-set hash. "Active" = {@code catalogue = USER AND userId = :userId AND
   * archivedAt IS NULL AND deletedAt IS NULL}. Single query, no N+1; the dedup service groups the
   * rows by {@code recipeId}.
   */
  @Query(
      """
      select r.id, i.ingredientMappingKey,
             (select count(ms) from com.example.mealprep.recipe.domain.entity.RecipeMethodStep ms
               where ms.version.id = v.id)
        from Recipe r
        join com.example.mealprep.recipe.domain.entity.RecipeVersion v
          on v.recipe.id = r.id
         and v.branch.id = r.currentBranchId
         and v.versionNumber = r.currentVersion
        join com.example.mealprep.recipe.domain.entity.RecipeIngredient i
          on i.version.id = v.id
       where r.userId = :userId
         and r.catalogue = com.example.mealprep.recipe.domain.entity.Catalogue.USER
         and r.archivedAt is null
         and r.deletedAt is null
      """)
  List<Object[]> findCurrentVersionIngredientKeysAndMethodCountForUser(
      @Param("userId") UUID userId);
}
