package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.ImportSource;
import com.example.mealprep.recipe.domain.entity.RecipeImport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RecipeImport}. Public so that the in-module {@code
 * domain.service.internal} package can inject it; cross-module isolation is enforced by {@code
 * RecipeBoundaryTest} (ArchUnit).
 */
public interface RecipeImportRepository extends JpaRepository<RecipeImport, UUID> {

  /**
   * Look up the (zero or one) provenance row for a recipe. Returns empty for manually-created
   * recipes from 01a (no import row was ever written).
   */
  Optional<RecipeImport> findByRecipeId(UUID recipeId);

  /**
   * Dedup probe used by {@code RecipeWriteApi.saveImportedRecipe} (discovery-01g). Returns the
   * existing provenance row whose {@code content_fingerprint} matches; empty if no prior import
   * shared the fingerprint. Backed by the partial UNIQUE index on {@code content_fingerprint}.
   */
  Optional<RecipeImport> findByContentFingerprint(String contentFingerprint);

  /**
   * Recipe ids imported by a batch/discovery {@code job_id} with the given provenance class,
   * ordered for deterministic batch processing. Backs {@code
   * RecipeUpdateService.archiveByImportJobId} / {@code unarchiveByImportJobId} (the G11 graph-batch
   * withdraw lever): one {@code jobId} per graph batch is the landed G06 invariant, and the {@code
   * sourceType} filter keeps a graph-batch withdraw from ever sweeping a discovery crawl's harvest
   * (which shares the {@code job_id} column but is stamped {@code WEB_DISCOVERED}).
   */
  @Query(
      """
      select ri.recipeId from RecipeImport ri
      where ri.jobId = :jobId and ri.sourceType = :sourceType
      order by ri.recipeId asc
      """)
  List<UUID> findRecipeIdsByJobIdAndSourceType(
      @Param("jobId") UUID jobId, @Param("sourceType") ImportSource sourceType);
}
