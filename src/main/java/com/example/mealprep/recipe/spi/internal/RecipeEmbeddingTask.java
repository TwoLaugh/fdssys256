package com.example.mealprep.recipe.spi.internal;

import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.ai.spi.EmbeddingTaskType;
import java.util.Optional;
import java.util.UUID;

/**
 * Recipe-side {@link EmbeddingTask} payload submitted by {@code RecipeEmbeddingListener} to {@code
 * AiService.embed}. Maps onto {@link EmbeddingTaskType#RECIPE_SEMANTIC_VECTOR} so the audit row
 * lands on the existing {@code EMBEDDING_RECIPE_SEMANTIC_VECTOR} {@code TaskType} value — no
 * cross-module enum churn.
 *
 * <p>LLD divergence: the LLD suggested a {@code RECIPE_VERSION} enum value, but the ai module
 * already ships {@code RECIPE_SEMANTIC_VECTOR} for exactly this purpose and the {@code switch}
 * statements in {@code AiServiceImpl.toTaskType} + {@code TestAiService.embeddingAuditType} would
 * need updating alongside any new value. Re-using the existing value keeps the cross-module surface
 * of recipe-01h to zero ai-module file edits.
 */
public record RecipeEmbeddingTask(UUID versionId, String inputText, UUID adapterTraceId)
    implements EmbeddingTask {

  @Override
  public EmbeddingTaskType type() {
    return EmbeddingTaskType.RECIPE_SEMANTIC_VECTOR;
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.empty();
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(adapterTraceId);
  }
}
