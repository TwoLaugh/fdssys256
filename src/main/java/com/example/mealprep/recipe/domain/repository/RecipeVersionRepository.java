package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import java.time.Instant;
import java.util.Collection;
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
 * Spring Data repository for {@link RecipeVersion}.
 *
 * <p>No multi-attribute {@code @EntityGraph} — {@code ingredients} and {@code methodSteps} are both
 * {@code @OneToMany List<>} and Hibernate throws {@code MultipleBagFetchException} if both are
 * fetched eagerly. The service touches each collection inside {@code @Transactional} to force lazy
 * load (4 SELECTs per read: version + ingredients + methodSteps + metadata + tags). The mapper
 * applies explicit {@code Comparator} ordering when building the DTO.
 */
public interface RecipeVersionRepository extends JpaRepository<RecipeVersion, UUID> {

  Optional<RecipeVersion> findFirstByRecipeIdAndBranchIdAndVersionNumber(
      UUID recipeId, UUID branchId, int versionNumber);

  /**
   * Version-history listing (recipe-5). Returns the versions on a branch of a recipe, newest
   * version-number first, bounded by {@link Pageable}. The service force-loads each row's lazy body
   * children inside the read transaction before mapping (see {@code touchLazyChildren}).
   */
  Page<RecipeVersion> findByRecipeIdAndBranchIdOrderByVersionNumberDesc(
      UUID recipeId, UUID branchId, Pageable pageable);

  /**
   * Batched current-version load for the library list read ({@code GET /api/v1/recipes}): one query
   * returns the current-pointer version row of every recipe on the page, with the two
   * {@code @OneToOne} children ({@code metadata}, {@code tags}) fetch-joined in the same statement
   * (no bag, so no {@code MultipleBagFetchException} risk). The two {@code List<>} bags are fetched
   * by the companion {@link #findWithIngredientsByIdIn} / {@link #findWithMethodStepsByIdIn}
   * queries into the same persistence context — constant queries per page instead of 4 lazy SELECTs
   * per row.
   */
  @Query(
      """
      select v from RecipeVersion v
        left join fetch v.metadata
        left join fetch v.tags
        join com.example.mealprep.recipe.domain.entity.Recipe r
          on r.id = v.recipe.id
         and v.branch.id = r.currentBranchId
         and v.versionNumber = r.currentVersion
       where r.id in :recipeIds
      """)
  List<RecipeVersion> findCurrentVersionsForRecipes(@Param("recipeIds") Collection<UUID> recipeIds);

  /**
   * Bag-initialising fetch for {@link #findCurrentVersionsForRecipes} — loads {@code ingredients}
   * for the given version ids into the current persistence context (single-bag fetch join is safe).
   * The caller may ignore the return value; the side effect is the initialised collections on the
   * already-managed entities.
   */
  @Query(
      "select distinct v from RecipeVersion v left join fetch v.ingredients where v.id in"
          + " :versionIds")
  List<RecipeVersion> findWithIngredientsByIdIn(@Param("versionIds") Collection<UUID> versionIds);

  /** Companion to {@link #findWithIngredientsByIdIn} for the {@code methodSteps} bag. */
  @Query(
      "select distinct v from RecipeVersion v left join fetch v.methodSteps where v.id in"
          + " :versionIds")
  List<RecipeVersion> findWithMethodStepsByIdIn(@Param("versionIds") Collection<UUID> versionIds);

  /**
   * Resolve the id of the recipe's current-branch current-version row in a single query. Used by
   * the manual-edit flow to load the parent version body without first hitting {@code Recipe}.
   */
  @Query(
      """
      select v.id from RecipeVersion v
       where v.recipe.id = :recipeId
         and v.branch.id = :branchId
         and v.versionNumber = :currentVersion
      """)
  Optional<UUID> findCurrentVersionId(
      @Param("recipeId") UUID recipeId,
      @Param("branchId") UUID branchId,
      @Param("currentVersion") int currentVersion);

  /**
   * Direct UPDATE for the embedding columns — bypasses Hibernate's full-entity save (which collides
   * with the entity's child collections + dirty-checking when the row is being touched by the
   * create flow's persistence context). Native SQL casts the bound varchar parameter to pgvector.
   * The async listener calls this exclusively.
   */
  @Modifying
  @Query(
      value =
          "UPDATE recipe_versions SET embedding = CAST(:embedding AS vector),"
              + " embedding_model_id = :modelId, embedded_at = :embeddedAt,"
              + " embedding_status = 'embedded' WHERE id = :id",
      nativeQuery = true)
  int updateEmbedding(
      @Param("id") UUID id,
      @Param("embedding") String embedding,
      @Param("modelId") String modelId,
      @Param("embeddedAt") Instant embeddedAt);

  /** Flip embedding_status to 'failed' without touching the rest of the row. */
  @Modifying
  @Query(
      value = "UPDATE recipe_versions SET embedding_status = 'failed' WHERE id = :id",
      nativeQuery = true)
  int markEmbeddingFailed(@Param("id") UUID id);
}
