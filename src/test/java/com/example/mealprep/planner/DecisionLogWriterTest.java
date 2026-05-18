package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogEntry;
import com.example.mealprep.planner.domain.service.internal.decisionlog.DecisionLogWriter;
import com.example.mealprep.planner.domain.service.internal.decisionlog.PlannerDecisionKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pure unit test for {@link DecisionLogWriter} (planner-01l). Verifies the fixed mapping
 * (scope_kind=PLANNER, scale=WEEK, kind stamped into inputs), reasoning truncation, REQUIRES_NEW
 * declaration, null-arg rejection, and the never-throw-to-caller contract. Mutation-strong: every
 * mapped field and the truncation boundary is asserted.
 */
@ExtendWith(MockitoExtension.class)
class DecisionLogWriterTest {

  @Mock private DecisionLogService decisionLogService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private DecisionLogWriter writer;

  @BeforeEach
  void setUp() {
    writer =
        new DecisionLogWriter(
            decisionLogService, objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  private DecisionLogEntry entry(ObjectNode inputs, String reasoning) {
    return new DecisionLogEntry(
        PlannerDecisionKind.STAGE_C_DONE,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        inputs,
        objectMapper.createObjectNode().put("chosenIndex", 2),
        reasoning,
        "user");
  }

  @Test
  void write_mapsFixedScopeAndStampsKindIntoInputs() {
    UUID assigned = UUID.randomUUID();
    when(decisionLogService.write(any(DecisionLogWriteRequest.class))).thenReturn(assigned);
    ObjectNode inputs = objectMapper.createObjectNode().put("rollupCount", 5);
    DecisionLogEntry e = entry(inputs, "variety beat cost");

    UUID result = writer.write(e);

    assertThat(result).isEqualTo(assigned);
    ArgumentCaptor<DecisionLogWriteRequest> cap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(cap.capture());
    DecisionLogWriteRequest req = cap.getValue();
    assertThat(req.scopeKind()).isEqualTo("PLANNER");
    assertThat(req.scopeId()).isEqualTo(e.planId());
    assertThat(req.scale()).isEqualTo(DecisionLogScale.WEEK);
    assertThat(req.traceId()).isEqualTo(e.traceId());
    assertThat(req.parentDecisionId()).isEqualTo(e.parentDecisionId());
    assertThat(req.actorUserId()).isEqualTo(e.actorUserId());
    assertThat(req.triggeredBy()).isEqualTo("user");
    assertThat(req.reasoning()).isEqualTo("variety beat cost");
    // kind stamped into inputs; original fields preserved; original node not mutated.
    assertThat(req.inputs().get("kind").asText()).isEqualTo("STAGE_C_DONE");
    assertThat(req.inputs().get("rollupCount").asInt()).isEqualTo(5);
    assertThat(inputs.has("kind")).isFalse();
  }

  @Test
  void write_truncatesReasoningToCap() {
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());
    String longText = "x".repeat(900);

    writer.write(entry(objectMapper.createObjectNode(), longText));

    ArgumentCaptor<DecisionLogWriteRequest> cap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(cap.capture());
    assertThat(cap.getValue().reasoning()).hasSize(DecisionLogWriter.MAX_REASONING_CHARS);
  }

  @Test
  void write_keepsReasoningAtExactlyCap() {
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());
    String exact = "y".repeat(DecisionLogWriter.MAX_REASONING_CHARS);

    writer.write(entry(objectMapper.createObjectNode(), exact));

    ArgumentCaptor<DecisionLogWriteRequest> cap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(cap.capture());
    assertThat(cap.getValue().reasoning()).isEqualTo(exact);
  }

  @Test
  void write_nullReasoning_passesNull() {
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());

    writer.write(entry(objectMapper.createObjectNode(), null));

    ArgumentCaptor<DecisionLogWriteRequest> cap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(cap.capture());
    assertThat(cap.getValue().reasoning()).isNull();
  }

  @Test
  void write_nullInputs_createsObjectWithKind() {
    when(decisionLogService.write(any())).thenReturn(UUID.randomUUID());
    DecisionLogEntry e =
        new DecisionLogEntry(
            PlannerDecisionKind.PLAN_GENERATION_START,
            UUID.randomUUID(),
            null,
            null,
            UUID.randomUUID(),
            null,
            null,
            "root",
            "user");

    writer.write(e);

    ArgumentCaptor<DecisionLogWriteRequest> cap =
        ArgumentCaptor.forClass(DecisionLogWriteRequest.class);
    verify(decisionLogService).write(cap.capture());
    assertThat(cap.getValue().inputs().get("kind").asText()).isEqualTo("PLAN_GENERATION_START");
  }

  @Test
  void write_returnsNull_whenUnderlyingWriteThrows() {
    when(decisionLogService.write(any())).thenThrow(new RuntimeException("db down"));

    UUID result = writer.write(entry(objectMapper.createObjectNode(), "x"));

    // Never propagates: a decision-log failure must not fail the audited operation.
    assertThat(result).isNull();
  }

  @Test
  void write_nullEntry_throwsIllegalArgument() {
    assertThatThrownBy(() -> writer.write(null)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(decisionLogService);
  }

  @Test
  void write_isAnnotatedRequiresNew() throws Exception {
    Method m = DecisionLogWriter.class.getDeclaredMethod("write", DecisionLogEntry.class);
    Transactional tx = m.getAnnotation(Transactional.class);
    assertThat(tx).as("DecisionLogWriter.write must be @Transactional").isNotNull();
    assertThat(tx.propagation())
        .as("ticket invariant #7 / DoD: REQUIRES_NEW so audit survives caller rollback")
        .isEqualTo(Propagation.REQUIRES_NEW);
  }

  @Test
  void write_scopeKindConstantIsPlanner() throws Exception {
    // Guards the admin endpoint's scope filter and the smoke test's scope query.
    assertThat(DecisionLogWriter.SCOPE_KIND).isEqualTo("PLANNER");
    // Public ctor is the documented injection surface (DecisionLogService, ObjectMapper, Clock).
    Constructor<?>[] ctors = DecisionLogWriter.class.getDeclaredConstructors();
    assertThat(ctors).hasSize(1);
    assertThat(ctors[0].getParameterCount()).isEqualTo(3);
  }
}
