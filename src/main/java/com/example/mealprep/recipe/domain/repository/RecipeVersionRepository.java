package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
