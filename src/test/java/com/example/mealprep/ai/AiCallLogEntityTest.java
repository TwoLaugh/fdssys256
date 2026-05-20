package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plain-old getter/setter coverage for {@link AiCallLog}. Baseline left 15 mutants uncovered
 * because no unit test exercised the entity directly — the IT path runs through Hibernate and isn't
 * in Pitest's surface. Pinning every accessor here kills the NullReturnVals / EmptyObjectReturnVals
 * / PrimitiveReturns mutants in one pass.
 */
class AiCallLogEntityTest {

  @Test
  void allFieldsRoundTrip_andEveryGetterReturnsItsField() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    AiCallLog row =
        new AiCallLog(
            id,
            userId,
            traceId,
            TaskType.RECIPE_ADAPTATION,
            ModelTier.MID,
            "claude-sonnet-4-6",
            "recipe/adapt",
            7,
            CallStatus.PENDING);

    // Constructor-driven immutables.
    assertThat(row.getId()).isEqualTo(id);
    assertThat(row.getUserId()).isEqualTo(userId);
    assertThat(row.getTraceId()).isEqualTo(traceId);
    assertThat(row.getTaskType()).isEqualTo(TaskType.RECIPE_ADAPTATION);
    assertThat(row.getModelTier()).isEqualTo(ModelTier.MID);
    assertThat(row.getModelId()).isEqualTo("claude-sonnet-4-6");
    assertThat(row.getPromptRefName()).isEqualTo("recipe/adapt");
    assertThat(row.getPromptRefVersion()).isEqualTo(7);
    assertThat(row.getStatus()).isEqualTo(CallStatus.PENDING);
    // Cost defaults to 0L in the constructor.
    assertThat(row.getCostMicroPence()).isEqualTo(0L);

    // Mutable setters.
    row.setRequestTokens(11);
    row.setResponseTokens(22);
    row.setLatencyMs(345);
    Instant completed = Instant.parse("2026-05-08T12:00:00Z");
    row.setCompletedAt(completed);
    row.setStatus(CallStatus.SUCCEEDED);
    row.setErrorKind(CallErrorKind.AI_UNAVAILABLE);
    row.setCostMicroPence(987_654L);

    assertThat(row.getRequestTokens()).isEqualTo(11);
    assertThat(row.getResponseTokens()).isEqualTo(22);
    assertThat(row.getLatencyMs()).isEqualTo(345);
    assertThat(row.getCompletedAt()).isEqualTo(completed);
    assertThat(row.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(row.getErrorKind()).isEqualTo(CallErrorKind.AI_UNAVAILABLE);
    assertThat(row.getCostMicroPence()).isEqualTo(987_654L);
  }

  /**
   * Kills NullReturnVals on accessors when the constructor was called with null reference values —
   * the getters must still return null, not a non-null sentinel.
   */
  @Test
  void constructorAllowsNullableFields_andGettersReturnNullForThem() {
    AiCallLog row =
        new AiCallLog(
            UUID.randomUUID(),
            null,
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku",
            null,
            null,
            CallStatus.PENDING);
    assertThat(row.getUserId()).isNull();
    assertThat(row.getTraceId()).isNull();
    assertThat(row.getPromptRefName()).isNull();
    assertThat(row.getPromptRefVersion()).isNull();
    assertThat(row.getRequestTokens()).isNull();
    assertThat(row.getResponseTokens()).isNull();
    assertThat(row.getLatencyMs()).isNull();
    assertThat(row.getCompletedAt()).isNull();
    assertThat(row.getErrorKind()).isNull();
  }

  /**
   * Kills PrimitiveReturns on getCostMicroPence (replaces with 0). We set a non-zero value and
   * assert that 0 is wrong.
   */
  @Test
  void getCostMicroPence_returnsExplicitNonZero() {
    AiCallLog row =
        new AiCallLog(
            UUID.randomUUID(),
            null,
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku",
            null,
            null,
            CallStatus.PENDING);
    row.setCostMicroPence(42L);
    assertThat(row.getCostMicroPence()).isNotZero().isEqualTo(42L);
  }
}
