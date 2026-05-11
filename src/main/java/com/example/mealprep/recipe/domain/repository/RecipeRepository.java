package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.Recipe;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
