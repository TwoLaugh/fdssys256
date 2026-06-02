package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.spi.TaskType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps each chat {@link TaskType} to the classpath location of its engineered dispatch prompt file
 * (the {@code prompts/<module>/<task>.txt} bodies shipped by the prompt-engineering work, ai-1).
 *
 * <p>This is the single wiring point between a {@link TaskType} and the prompt the dispatcher
 * renders: {@link AnthropicClient#renderUserMessage} resolves the file here, loads it from the
 * classpath, and renders it with the task's {@link com.example.mealprep.ai.spi.AiTask#variables()}.
 * Both chat providers ({@link AnthropicClient} / {@link OpenAiChatClient}) and the {@link
 * TokenCapGuard} pre-check all flow through that one render method, so the prompt body never drifts
 * between the cap estimate and the wire.
 *
 * <p>Tasks <b>without</b> an engineered file (e.g. the embedding task types, or the not-yet-wired
 * {@code INTAKE_PARSE} / {@code INGREDIENT_MAPPING} / {@code RECIPE_HTML_EXTRACTION} tasks) are
 * absent from the map; for those the render path keeps the legacy fallback (an explicit {@code
 * "prompt"} variable, else a JSON dump of the variables).
 */
final class PromptFiles {

  private static final Map<TaskType, String> BY_TASK_TYPE = buildMapping();

  private PromptFiles() {}

  /**
   * The classpath path of the engineered prompt file for a task type, or empty when the task has no
   * file (so the caller keeps its existing fallback).
   */
  static Optional<String> classpathFor(TaskType type) {
    return Optional.ofNullable(BY_TASK_TYPE.get(type));
  }

  private static Map<TaskType, String> buildMapping() {
    Map<TaskType, String> map = new EnumMap<>(TaskType.class);
    map.put(TaskType.FEEDBACK_CLASSIFICATION, "prompts/feedback/classify-feedback.txt");
    map.put(TaskType.DISCOVERY_FILTERING, "prompts/discovery/candidate-filter.txt");
    map.put(TaskType.RECIPE_ADAPTATION, "prompts/adaptation/recipe-adaptation.txt");
    map.put(TaskType.PLANNER_STAGE_C, "prompts/planner/stage-c-pick.txt");
    map.put(TaskType.PLANNER_PHASE2_AUGMENTATION, "prompts/planner/phase2-augmentation.txt");
    map.put(TaskType.PREFERENCE_DELTA_UPDATE, "prompts/preference/taste-profile-delta-user.txt");
    return map;
  }
}
