package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
