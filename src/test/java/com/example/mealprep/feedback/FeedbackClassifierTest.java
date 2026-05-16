package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.feedback.api.dto.ClassificationOutput;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationContext;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationTask;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassifier;
import com.example.mealprep.feedback.spi.Destination;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Unit tests for {@link FeedbackClassifier} — verifies pass-through to {@link AiService}. */
class FeedbackClassifierTest {

  private final AiService aiService = Mockito.mock(AiService.class);
  private final FeedbackClassifier classifier = new FeedbackClassifier(aiService);

  @Test
  @SuppressWarnings("unchecked")
  void classify_returnsAiServiceResult_andPassesTaskOfCorrectType() {
    ClassificationResult canned = sampleResult();
    when(aiService.execute(any(AiTask.class))).thenReturn(canned);

    FeedbackClassificationContext context = sampleContext();
    ClassificationResult out = classifier.classify(context);

    assertThat(out).isSameAs(canned);

    ArgumentCaptor<AiTask<ClassificationResult>> captor = ArgumentCaptor.forClass(AiTask.class);
    verify(aiService).execute(captor.capture());
    AiTask<ClassificationResult> task = captor.getValue();
    assertThat(task).isInstanceOf(FeedbackClassificationTask.class);
    assertThat(task.type()).isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
    assertThat(task.outputType()).isEqualTo(ClassificationResult.class);
    assertThat(task.userId()).isEqualTo(Optional.of(context.userId()));
    assertThat(task.traceId()).isEqualTo(Optional.of(context.traceId()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void classify_propagatesAiUnavailable() {
    when(aiService.execute(any(AiTask.class)))
        .thenThrow(new AiUnavailableException("upstream 503"));

    assertThatThrownBy(() -> classifier.classify(sampleContext()))
        .isInstanceOf(AiUnavailableException.class);
  }

  @Test
  void classify_nullContext_throws() {
    assertThatThrownBy(() -> classifier.classify(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------- helpers ----------------

  private FeedbackClassificationContext sampleContext() {
    UiContextDto ui = new UiContextDto(Screen.GENERAL, null, null, null, null, null);
    return new FeedbackClassificationContext(
        UUID.randomUUID(), UUID.randomUUID(), "hello", ui, Optional.empty(), Optional.empty(), 1);
  }

  private ClassificationResult sampleResult() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("noted", "ok");
    return new ClassificationResult(
        List.of(
            new ClassificationOutput(Destination.RECIPE, new BigDecimal("0.90"), "salty", payload)),
        new BigDecimal("0.90"),
        null);
  }
}
