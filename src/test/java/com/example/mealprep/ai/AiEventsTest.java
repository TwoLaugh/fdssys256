package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.event.AiCallFailedEvent;
import com.example.mealprep.ai.event.AiCallSucceededEvent;
import com.example.mealprep.ai.event.CostBudgetExceededEvent;
import com.example.mealprep.ai.event.PromptTemplateLoadedEvent;
import com.example.mealprep.ai.spi.TaskType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code scopeKind} and {@code scopeId} overrides on every {@code ai} module event.
 * Baseline had NO_COVERAGE on these because no unit test instantiated the records directly. Each
 * event publishes a known scope-key string and scopeId — kills the EmptyObjectReturnVals (replacing
 * scopeKind with "") and NullReturnVals (replacing scopeId with null) mutators.
 */
class AiEventsTest {

  @Test
  void aiCallSucceededEvent_scopeKindAndId_areCallScopeAndCallId() {
    UUID callId = UUID.randomUUID();
    AiCallSucceededEvent ev =
        new AiCallSucceededEvent(
            callId,
            TaskType.FEEDBACK_CLASSIFICATION,
            UUID.randomUUID(),
            42,
            123L,
            UUID.randomUUID(),
            Instant.parse("2026-05-08T12:00:00Z"));
    assertThat(ev.scopeKind()).isEqualTo("ai-call");
    assertThat(ev.scopeId()).isEqualTo(callId);
  }

  @Test
  void aiCallFailedEvent_scopeKindAndId_areCallScopeAndCallId() {
    UUID callId = UUID.randomUUID();
    AiCallFailedEvent ev =
        new AiCallFailedEvent(
            callId,
            TaskType.FEEDBACK_CLASSIFICATION,
            UUID.randomUUID(),
            CallErrorKind.AI_UNAVAILABLE,
            UUID.randomUUID(),
            Instant.parse("2026-05-08T12:00:00Z"));
    assertThat(ev.scopeKind()).isEqualTo("ai-call");
    assertThat(ev.scopeId()).isEqualTo(callId);
  }

  @Test
  void costBudgetExceededEvent_scopeKindAndId_areBudgetScopeAndUserId() {
    UUID userId = UUID.randomUUID();
    CostBudgetExceededEvent ev =
        new CostBudgetExceededEvent(
            userId,
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            Duration.ofHours(24),
            UUID.randomUUID(),
            Instant.parse("2026-05-08T12:00:00Z"));
    assertThat(ev.scopeKind()).isEqualTo("ai-budget");
    assertThat(ev.scopeId()).isEqualTo(userId);
  }

  @Test
  void promptTemplateLoadedEvent_scopeKindAndId_arePromptScopeAndTemplateId() {
    UUID templateId = UUID.randomUUID();
    PromptTemplateLoadedEvent ev =
        new PromptTemplateLoadedEvent(
            templateId, "classify", 3, UUID.randomUUID(), Instant.parse("2026-05-08T12:00:00Z"));
    assertThat(ev.scopeKind()).isEqualTo("prompt-template");
    assertThat(ev.scopeId()).isEqualTo(templateId);
  }
}
