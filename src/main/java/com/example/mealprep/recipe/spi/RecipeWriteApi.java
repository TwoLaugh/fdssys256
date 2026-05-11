package com.example.mealprep.recipe.spi;

import com.example.mealprep.recipe.api.dto.CharacterFingerprintDto;
import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal SPI consumed by the (future) Adaptation Pipeline to write through to the recipe module's
 * append-only history. Per LLD §RecipeWriteApi lines 583-624.
 *
 * <p>Isolated from cross-module callers: an ArchUnit rule in {@code RecipeBoundaryTest} asserts
 * that no class outside {@code com.example.mealprep.recipe..} depends on classes in {@code
 * com.example.mealprep.recipe.spi..}. The single permitted external consumer is the Adaptation
 * Pipeline module (not yet built) — it lands in a sibling module and the rule will need to be
 * adjusted when that pipeline module exists.
 *
 * <p>{@link #storeEmbedding} extends the LLD's two-arg signature with a {@code modelId} param per
 * LLD line 117 (schema column {@code embedding_model_id} requires the caller to supply the model
 * id).
 */
public interface RecipeWriteApi {

  /**
   * Persist a new {@code RecipeVersion} on the recipe's current branch with optimistic race-check
   * against the recipe's current head. Mismatch → {@code RecipeVersionConflictException}. Publishes
   * {@code RecipeUpdatedEvent} + {@code RecipeAdaptedEvent(NEW_VERSION)} {@code AFTER_COMMIT}.
   */
  RecipeVersionDto saveAdaptedVersion(SaveAdaptedVersionCommand command);

  /**
   * Persist a new {@code RecipeBranch} + v1 {@code RecipeVersion} on it. Does NOT bump {@code
   * recipe.currentVersion} (branch creation doesn't move the head). Publishes {@code
   * RecipeAdaptedEvent(BRANCH)} {@code AFTER_COMMIT}.
   */
  RecipeBranchDto saveAdaptedBranch(SaveAdaptedBranchCommand command);

  /**
   * Persist a substitution with {@code state = ACCEPTED} and {@code adapterTraceId} populated;
   * delegates the validation/aggregate handling to the existing 01e substitution flow. Publishes
   * {@code RecipeAdaptedEvent(SUBSTITUTION)} {@code AFTER_COMMIT}.
   */
  RecipeSubstitutionDto saveAdaptedSubstitution(SaveAdaptedSubstitutionCommand command);

  /**
   * Back-fill the version's nutrition status + per-serving JSON. Publishes {@code
   * RecipeEvolvedEvent(NUTRITION_RECALCULATED)} {@code AFTER_COMMIT}. Permitted append-only
   * mutation per LLD line 130.
   */
  void updateNutritionStatus(UUID versionId, NutritionStatus status, JsonNode nutritionPerServing);

  /**
   * Back-fill the version's character fingerprint. Publishes {@code
   * RecipeEvolvedEvent(FINGERPRINT_REFRESHED)} {@code AFTER_COMMIT}. Permitted append-only mutation
   * per LLD line 130.
   */
  void updateCharacterFingerprint(UUID versionId, CharacterFingerprintDto fingerprint);

  /** Update a branch's divergence score. No event. */
  void updateBranchDivergence(UUID branchId, BigDecimal divergenceScore);

  /**
   * Back-fill the version's embedding vector + model id; flips {@code embedding_status} from {@code
   * pending} to {@code embedded}. Publishes {@code RecipeEvolvedEvent(EMBEDDING_STORED)} {@code
   * AFTER_COMMIT}. Permitted append-only mutation per LLD line 130.
   *
   * <p>LLD divergence: signature widened by one param ({@code modelId}) vs the line-599 signature,
   * to satisfy the {@code embedding_model_id} column requirement. The {@code embedding} vector
   * column itself + the pgvector partial HNSW index land in recipe-01h alongside {@code CREATE
   * EXTENSION vector}; 01f records the model id + flips status, the vector storage is a no-op until
   * 01h's migration.
   */
  void storeEmbedding(UUID versionId, float[] embedding, String modelId);
}
