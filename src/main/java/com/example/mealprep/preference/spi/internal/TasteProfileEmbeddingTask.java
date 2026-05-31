package com.example.mealprep.preference.spi.internal;

import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.ai.spi.EmbeddingTaskType;
import java.util.Optional;
import java.util.UUID;

/**
 * Preference-side {@link EmbeddingTask} payload submitted by {@code TasteProfileEmbeddingListener}
 * to {@code AiService.embed}. Maps onto {@link EmbeddingTaskType#PREFERENCE_TASTE_VECTOR} so the
 * audit row lands on the existing {@code EMBEDDING_PREFERENCE_TASTE_VECTOR} {@code TaskType} value
 * — no cross-module enum churn. Sibling of {@code recipe.spi.internal.RecipeEmbeddingTask}.
 *
 * <p>{@code ownerUserId} is populated (taste vectors are user-scoped) so the AI cost guard
 * attributes embedding spend per user, and the audit row carries the owning user. The record
 * components are named {@code ownerUserId} / {@code originTraceId} (not {@code userId} / {@code
 * traceId}) so the generated component accessors do not clash with the {@link EmbeddingTask}
 * interface methods, which return {@code Optional<UUID>} — same pattern as {@code
 * RecipeEmbeddingTask.adapterTraceId}.
 */
public record TasteProfileEmbeddingTask(UUID ownerUserId, String inputText, UUID originTraceId)
    implements EmbeddingTask {

  @Override
  public EmbeddingTaskType type() {
    return EmbeddingTaskType.PREFERENCE_TASTE_VECTOR;
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(ownerUserId);
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(originTraceId);
  }
}
