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
  PLANNER_PHASE2_AUGMENTATION
}
