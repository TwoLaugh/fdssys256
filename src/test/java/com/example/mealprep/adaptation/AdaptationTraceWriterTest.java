package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationTraceWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit coverage for {@link AdaptationTraceWriter#write} — wholly NO_COVERAGE before. Targets the
 * two null-coalescing ternaries (L54 {@code inputsSnapshot}, L59 {@code candidates}) and the L73
 * {@code repository.saveAndFlush(trace).getId()} return. {@code AdaptationTraceRepository} is a
 * Spring-Data repository (cross-boundary persistence seam) so mocking it is allowed.
 */
class AdaptationTraceWriterTest {

  private final AdaptationTraceRepository repo = mock(AdaptationTraceRepository.class);
  private final AdaptationTraceWriter writer = new AdaptationTraceWriter(repo);

  private AdaptationTrace capture(AdaptationTraceWriter.TraceData data) {
    when(repo.saveAndFlush(any(AdaptationTrace.class))).thenAnswer(inv -> inv.getArgument(0));
    writer.write(data);
    ArgumentCaptor<AdaptationTrace> cap = ArgumentCaptor.forClass(AdaptationTrace.class);
    verify(repo).saveAndFlush(cap.capture());
    return cap.getValue();
  }

  private static AdaptationTraceWriter.TraceData data(
      com.fasterxml.jackson.databind.JsonNode inputs,
      com.fasterxml.jackson.databind.JsonNode candidates) {
    return new AdaptationTraceWriter.TraceData(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        JobSource.IMPORT,
        "tpl",
        "v1",
        null,
        inputs,
        null,
        candidates,
        null,
        AdaptationClassification.VERSION,
        null,
        null,
        null,
        ValidationResult.PASSED,
        OutcomeKind.VERSION_CREATED,
        null,
        42);
  }

  @Test
  void null_inputs_and_candidates_default_to_empty_json_nodes() {
    // L54/L59: `data.x() == null ? emptyNode : data.x()`. NegateConditionals would flip
    // the branch and produce a null column / wrong node; assert the empty defaults.
    AdaptationTrace t = capture(data(null, null));
    assertThat(t.getInputsSnapshot()).isNotNull();
    assertThat(t.getInputsSnapshot().isObject()).isTrue();
    assertThat(t.getInputsSnapshot()).isEmpty();
    assertThat(t.getCandidates()).isNotNull();
    assertThat(t.getCandidates().isArray()).isTrue();
    assertThat(t.getCandidates()).isEmpty();
  }

  @Test
  void non_null_inputs_and_candidates_pass_through_unchanged() {
    var inputs = JsonNodeFactory.instance.objectNode().put("k", "v");
    var cands = JsonNodeFactory.instance.arrayNode().add(1).add(2);
    AdaptationTrace t = capture(data(inputs, cands));
    assertThat(t.getInputsSnapshot()).isEqualTo(inputs);
    assertThat(t.getCandidates()).isEqualTo(cands);
  }

  @Test
  void write_returns_the_persisted_trace_id() {
    // L73: returns repository.saveAndFlush(trace).getId(). Stub a trace whose id differs
    // from any builder-generated id so a wrong/null return is detectable.
    UUID persistedId = UUID.randomUUID();
    AdaptationTrace persisted = mock(AdaptationTrace.class);
    when(persisted.getId()).thenReturn(persistedId);
    when(repo.saveAndFlush(any(AdaptationTrace.class))).thenReturn(persisted);

    UUID returned = writer.write(data(null, null));

    assertThat(returned).isEqualTo(persistedId);
  }
}
