package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Concrete {@link AiTask} implementation for the feedback classifier. Per lld/feedback.md
 * §Classifier (AiTask) lines 530-547.
 *
 * <p>The prompt body is owned by the prompt-engineering work track (LLD line 568). 01c ships the
 * task shape — when the template at {@code prompts/feedback/classify-feedback.txt} (or its current
 * resource location) is missing, {@code AiService.execute} will surface that as an {@code
 * AiInvalidRequestException}.
 */
public final class FeedbackClassificationTask implements AiTask<ClassificationResult> {

  /** Prompt name handed to the renderer; resolved against the prompt-template service. */
  public static final String PROMPT_NAME = "feedback/classify-feedback";

  /** Prompt version owned by the feedback module. Bumped when prompt 04 ships a v2. */
  public static final int PROMPT_VERSION = 1;

  private final FeedbackClassificationContext context;

  public FeedbackClassificationTask(FeedbackClassificationContext context) {
    if (context == null) {
      throw new IllegalArgumentException("context must not be null");
    }
    this.context = context;
  }

  @Override
  public TaskType type() {
    return TaskType.FEEDBACK_CLASSIFICATION;
  }

  @Override
  public ModelTier tier() {
    // Feedback classification is the cheap-tier slot per ai.md §SPI.
    return ModelTier.CHEAP;
  }

  @Override
  public PromptRef prompt() {
    return new PromptRef(PROMPT_NAME, PROMPT_VERSION);
  }

  @Override
  public Class<ClassificationResult> outputType() {
    return ClassificationResult.class;
  }

  @Override
  public Map<String, Object> variables() {
    return context.toRendererMap();
  }

  @Override
  public Optional<List<ToolDefinition>> tools() {
    return Optional.of(List.of(ToolDefinitions.classifyFeedback()));
  }

  @Override
  public Optional<UUID> userId() {
    return Optional.ofNullable(context.userId());
  }

  @Override
  public Optional<UUID> traceId() {
    return Optional.ofNullable(context.traceId());
  }
}
