package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.core.audit.domain.service.internal.TraceContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for {@link TraceContext}. Verifies MDC put/remove, nested-scope restoration, and
 * cleanup-on-exception per lld/core.md §Test Plan.
 */
class TraceContextTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void currentTraceId_isNull_whenUnset() {
    assertThat(TraceContext.currentTraceId()).isNull();
  }

  @Test
  void requireTraceId_throws_whenUnset() {
    assertThatThrownBy(TraceContext::requireTraceId).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void setTraceId_thenCurrentTraceId_roundTrips() {
    UUID traceId = UUID.randomUUID();
    TraceContext.setTraceId(traceId);
    assertThat(TraceContext.currentTraceId()).isEqualTo(traceId);
    assertThat(TraceContext.requireTraceId()).isEqualTo(traceId);
  }

  @Test
  void clear_removesTheEntry() {
    TraceContext.setTraceId(UUID.randomUUID());
    TraceContext.clear();
    assertThat(TraceContext.currentTraceId()).isNull();
  }

  @Test
  void runWithTraceId_putsDuringBody_andRemovesAfter() {
    UUID traceId = UUID.randomUUID();
    UUID[] seenInside = new UUID[1];

    TraceContext.runWithTraceId(traceId, () -> seenInside[0] = TraceContext.currentTraceId());

    assertThat(seenInside[0]).isEqualTo(traceId);
    assertThat(TraceContext.currentTraceId()).as("MDC cleared after body").isNull();
  }

  @Test
  void runWithTraceId_nested_restoresOuterTraceIdOnExit() {
    UUID outer = UUID.randomUUID();
    UUID inner = UUID.randomUUID();

    TraceContext.runWithTraceId(
        outer,
        () -> {
          assertThat(TraceContext.currentTraceId()).isEqualTo(outer);
          TraceContext.runWithTraceId(
              inner, () -> assertThat(TraceContext.currentTraceId()).isEqualTo(inner));
          // Inner scope exited — the outer trace id is restored, not cleared.
          assertThat(TraceContext.currentTraceId()).isEqualTo(outer);
        });

    assertThat(TraceContext.currentTraceId()).isNull();
  }

  @Test
  void runWithTraceId_cleansUp_whenBodyThrows() {
    UUID traceId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                TraceContext.runWithTraceId(
                    traceId,
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(TraceContext.currentTraceId()).as("MDC cleared even on exception").isNull();
  }

  @Test
  void callWithTraceId_returnsResult_andCleansUp() throws Exception {
    UUID traceId = UUID.randomUUID();

    String result =
        TraceContext.callWithTraceId(traceId, () -> "trace=" + TraceContext.currentTraceId());

    assertThat(result).isEqualTo("trace=" + traceId);
    assertThat(TraceContext.currentTraceId()).isNull();
  }

  @Test
  void callWithTraceId_restoresPrevious_whenNested() throws Exception {
    UUID outer = UUID.randomUUID();
    UUID inner = UUID.randomUUID();

    TraceContext.setTraceId(outer);
    String innerSeen =
        TraceContext.callWithTraceId(inner, () -> TraceContext.currentTraceId().toString());

    assertThat(innerSeen).isEqualTo(inner.toString());
    assertThat(TraceContext.currentTraceId()).as("previous restored").isEqualTo(outer);
  }
}
