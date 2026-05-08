package com.example.mealprep.ai.spi;

/**
 * Universe of AI completion tasks dispatched through {@link
 * com.example.mealprep.ai.domain.service.AiService}. One value per design under {@code
 * lld/prompts/}.
 *
 * <p>The dispatcher uses the task type to resolve a {@link ModelTier}, per-task token / timeout
 * caps, and a circuit breaker keyed by name. New entries land alongside their owning module's
 * prompt template.
 */
public enum TaskType {
  PREFERENCE_DELTA_UPDATE,
  INGREDIENT_MAPPING,
  INTAKE_PARSE,
  FEEDBACK_CLASSIFICATION,
  RECIPE_ADAPTATION,
  RECIPE_HTML_EXTRACTION,
  DISCOVERY_FILTERING,
  PLANNER_STAGE_C,
  PLANNER_PHASE2_AUGMENTATION,
  // Embedding sources — one per EmbeddingTaskType. Stored on ai_call_log.task_type so 01b's
  // budget guard sums embedding spend alongside completion spend. The mapping is owned by
  // AiServiceImpl.toTaskType(EmbeddingTaskType).
  EMBEDDING_PREFERENCE_TASTE_VECTOR,
  EMBEDDING_RECIPE_SEMANTIC_VECTOR,
  EMBEDDING_JOURNAL_ENTRY_VECTOR
}
