package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.nutrition.spi.internal.NutritionFeedbackReverterImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NutritionFeedbackReverterImpl} — nutrition revert is log-only / best-effort
 * (feedback-01h §12-13). The 01i feedback path is a direct target write with no clean inverse
 * (C-IMP-021 deferred); the reverter writes nothing and must never throw, with or without payload.
 */
class NutritionFeedbackReverterTest {

  private final NutritionFeedbackReverterImpl reverter = new NutritionFeedbackReverterImpl();

  @Test
  void revert_feedbackAdjustment_isLogOnlyAndDoesNotThrow() {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("target", "protein_g");
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("originTrace", "feedback-" + UUID.randomUUID());
    RevertContext ctx =
        new RevertContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Destination.NUTRITION,
            payload,
            result);

    assertThatCode(() -> reverter.revert(ctx)).doesNotThrowAnyException();
  }

  @Test
  void revert_nullPayloadAndResult_isLogOnlyAndDoesNotThrow() {
    RevertContext ctx =
        new RevertContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Destination.NUTRITION,
            null,
            null);

    assertThatCode(() -> reverter.revert(ctx)).doesNotThrowAnyException();
  }
}
