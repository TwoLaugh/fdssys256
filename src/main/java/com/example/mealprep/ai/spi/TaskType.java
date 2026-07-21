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
  NUTRITION_INGREDIENT_PARSE,
  NUTRITION_INGREDIENT_MATCH,
  // Estimate micronutrients a data source could not supply (no USDA/recipe value), from the
  // recipe's name + ingredients. Low-trust by design — the result is tagged source="estimated"
  // + a confidence, never blended with measured/derived data without that flag.
  NUTRIENT_ESTIMATION,
  PLANNER_STAGE_C,
  PLANNER_PHASE2_AUGMENTATION,
  // Culinary-appropriateness gate for in-meal additions (Phase 2): the deterministic planner picks
  // WHICH whole foods close the day's gap; this assigns each to the most sensible meal slot +
  // writes
  // the pairing note ("½ avocado on the taco salad"). Skippable — falls back to deterministic
  // placement when the AI is unavailable.
  PLANNER_ADDITION_PAIRING,
  // Embedding sources — one per EmbeddingTaskType. Stored on ai_call_log.task_type so 01b's
  // budget guard sums embedding spend alongside completion spend. The mapping is owned by
  // AiServiceImpl.toTaskType(EmbeddingTaskType).
  EMBEDDING_PREFERENCE_TASTE_VECTOR,
  EMBEDDING_RECIPE_SEMANTIC_VECTOR,
  EMBEDDING_JOURNAL_ENTRY_VECTOR
}
