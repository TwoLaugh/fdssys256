package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists an {@link AdaptationTrace} in a {@link Propagation#REQUIRES_NEW} inner transaction so a
 * rolled-back outer worker tx still keeps the diagnostic. Same pattern as the AI module's call
 * ledger writer.
 *
 * <p>Per ticket 01c §Step 8 / LLD lines 765-766. Per decisions/0010 round-7 gotcha: {@code
 * REQUIRES_NEW} is one of two allowed propagations alongside {@code NOT_SUPPORTED}.
 */
@Component
public class AdaptationTraceWriter {

  private final AdaptationTraceRepository repository;

  public AdaptationTraceWriter(AdaptationTraceRepository repository) {
    this.repository = repository;
  }

  /**
   * Write the trace row in a new inner transaction. Returns the persisted trace id.
   *
   * @param data the immutable bundle of trace inputs/results
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID write(TraceData data) {
    AdaptationTrace trace =
        AdaptationTrace.builder()
            .id(UUID.randomUUID())
            .jobId(data.jobId())
            .recipeId(data.recipeId())
            .traceId(data.traceId())
            .source(data.source())
            .promptTemplateName(data.promptTemplateName())
            .promptTemplateVersion(data.promptTemplateVersion())
            .aiCallId(data.aiCallId())
            .inputsSnapshot(
                data.inputsSnapshot() == null
                    ? JsonNodeFactory.instance.objectNode()
                    : data.inputsSnapshot())
            .rawAiResponse(data.rawAiResponse())
            .candidates(
                data.candidates() == null
                    ? JsonNodeFactory.instance.arrayNode()
                    : data.candidates())
            .chosenCandidateIndex(data.chosenCandidateIndex())
            .classificationDecision(data.classificationDecision())
            .finalDiff(data.finalDiff())
            .confidence(data.confidence())
            .characterPreservationScore(data.characterPreservationScore())
            .validationResult(data.validationResult())
            .outcomeKind(data.outcomeKind())
            .outcomeTargetId(data.outcomeTargetId())
            .durationMs(data.durationMs())
            .createdAt(Instant.now())
            .build();
    return repository.saveAndFlush(trace).getId();
  }

  /** Immutable bundle of fields needed to build an {@link AdaptationTrace}. */
  public record TraceData(
      UUID jobId,
      UUID recipeId,
      UUID traceId,
      JobSource source,
      String promptTemplateName,
      String promptTemplateVersion,
      @Nullable UUID aiCallId,
      @Nullable JsonNode inputsSnapshot,
      @Nullable JsonNode rawAiResponse,
      @Nullable JsonNode candidates,
      @Nullable Integer chosenCandidateIndex,
      @Nullable AdaptationClassification classificationDecision,
      @Nullable JsonNode finalDiff,
      @Nullable BigDecimal confidence,
      @Nullable BigDecimal characterPreservationScore,
      ValidationResult validationResult,
      OutcomeKind outcomeKind,
      @Nullable UUID outcomeTargetId,
      int durationMs) {}
}
