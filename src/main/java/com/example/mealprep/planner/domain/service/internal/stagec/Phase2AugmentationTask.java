package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Concrete {@link AiTask} for Phase-2 augmentation. Per lld/planner.md §{@code
 * Phase2AugmentationTask} (lines 889-911) and ticket planner-01h.
 *
 * <p><b>SPI reconciliation:</b> the LLD/ticket pseudocode used the older {@code
 * getTaskType()/getContext()/getResponseType()/getTimeoutOverride()} hooks. The merged {@link
 * AiTask} SPI is the lean {@code type/tier/prompt/outputType/variables/tools/userId/traceId}
 * surface; per-task timeout + tier resolution is owned by the AI dispatcher keyed off {@link
 * TaskType} (same reconciliation as {@code RecipeAdaptationTask}). The {@code TaskType} is {@link
 * TaskType#PLANNER_PHASE2_AUGMENTATION} (the enum's actual name; the ticket's {@code
 * PLAN_AUGMENTATION} was the pre-merge label). Frontier-tier ({@link ModelTier#HIGH}) per LLD line
 * 890 "frontier tier".
 */
public final class Phase2AugmentationTask implements AiTask<Phase2AugmentationResponse> {

  /** Prompt template name resolved by the AI module's prompt-template service. */
  public static final String PROMPT_NAME = "planner/phase2-augmentation";

  /** Prompt version owned by the planner module. */
  public static final int PROMPT_VERSION = 1;

  private final CandidatePlanRollupDto chosenPlanRollup;
  private final String constraintsSummary;
  private final List<Map<String, Object>> nutritionGapsPerDay;
  private final int maxAugmentations;
  private final int maxRefineDirectives;
  private final UUID primaryUserId;
  private final UUID traceId;

  Phase2AugmentationTask(
      CandidatePlanRollupDto chosenPlanRollup,
      String constraintsSummary,
      List<Map<String, Object>> nutritionGapsPerDay,
      int maxAugmentations,
      int maxRefineDirectives,
      UUID primaryUserId,
      UUID traceId) {
    this.chosenPlanRollup = chosenPlanRollup;
    this.constraintsSummary = constraintsSummary;
    this.nutritionGapsPerDay = nutritionGapsPerDay;
    this.maxAugmentations = maxAugmentations;
    this.maxRefineDirectives = maxRefineDirectives;
    this.primaryUserId = primaryUserId;
    this.traceId = traceId;
  }

  @Override
  public TaskType type() {
    return TaskType.PLANNER_PHASE2_AUGMENTATION;
  }

  @Override
  public ModelTier tier() {
    // LLD line 890: Phase 2 is the frontier tier.
    return ModelTier.HIGH;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<Phase2AugmentationResponse> outputType() {
    return Phase2AugmentationResponse.class;
  }

  @Override
  public Map<String, Object> variables() {
    return Map.of(
        "chosen_plan", orEmpty(chosenPlanRollup),
        "constraints_summary", orEmpty(constraintsSummary),
        "nutrition_gaps", orEmpty(nutritionGapsPerDay),
        "max_augmentations", maxAugmentations,
        "max_refine_directives", maxRefineDirectives);
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.of(List.of(Phase2ToolDefinitions.phase2Augmentation()));
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(primaryUserId);
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(traceId);
  }

  /** Null-safe substitution so {@code Map.of} (null-hostile) never NPEs on a sparse context. */
  private static Object orEmpty(Object value) {
    return value == null ? "" : value;
  }
}
