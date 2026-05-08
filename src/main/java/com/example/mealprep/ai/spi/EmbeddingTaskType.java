package com.example.mealprep.ai.spi;

/**
 * Universe of embedding sources dispatched through {@link
 * com.example.mealprep.ai.domain.service.AiService#embed}. Each value identifies the downstream
 * consumer of the resulting vector so the call log can attribute spend per source.
 *
 * <p>The dispatcher maps each value onto a {@link TaskType} entry of the form {@code EMBEDDING_*}
 * for storage in {@code ai_call_log.task_type}; this lets 01b's per-user cost guard sum embedding
 * spend alongside completion spend without a schema change.
 */
public enum EmbeddingTaskType {
  PREFERENCE_TASTE_VECTOR,
  RECIPE_SEMANTIC_VECTOR,
  JOURNAL_ENTRY_VECTOR
}
