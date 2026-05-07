package com.example.mealprep.core.testdata;

import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;

/**
 * Test data builder for {@link DecisionLogWriteRequest}. Provides sensible defaults so tests only
 * state the fields they care about.
 */
public final class DecisionLogTestData {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private DecisionLogTestData() {}

  public static Builder builder() {
    return new Builder();
  }

  public static JsonNode emptyJson() {
    return JsonNodeFactory.instance.objectNode();
  }

  public static JsonNode jsonObject(String key, String value) {
    return JsonNodeFactory.instance.objectNode().put(key, value);
  }

  public static class Builder {
    private UUID traceId = UUID.randomUUID();
    private UUID parentDecisionId; // null
    private String scopeKind = "test-scope";
    private UUID scopeId = UUID.randomUUID();
    private DecisionLogScale scale = DecisionLogScale.OTHER;
    private String triggeredBy = "unit-test";
    private UUID actorUserId; // null
    private JsonNode inputs = emptyJson();
    private JsonNode candidates; // null
    private JsonNode chosen; // null
    private String reasoning; // null
    private JsonNode emittedDirective; // null
    private int iteration = 0;
    private Integer durationMs; // null

    public Builder withTraceId(UUID traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder withParentDecisionId(UUID parentDecisionId) {
      this.parentDecisionId = parentDecisionId;
      return this;
    }

    public Builder withScope(String scopeKind, UUID scopeId) {
      this.scopeKind = scopeKind;
      this.scopeId = scopeId;
      return this;
    }

    public Builder withScale(DecisionLogScale scale) {
      this.scale = scale;
      return this;
    }

    public Builder withTriggeredBy(String triggeredBy) {
      this.triggeredBy = triggeredBy;
      return this;
    }

    public Builder withActorUserId(UUID actorUserId) {
      this.actorUserId = actorUserId;
      return this;
    }

    public Builder withInputs(JsonNode inputs) {
      this.inputs = inputs;
      return this;
    }

    public Builder withCandidates(JsonNode candidates) {
      this.candidates = candidates;
      return this;
    }

    public Builder withChosen(JsonNode chosen) {
      this.chosen = chosen;
      return this;
    }

    public Builder withReasoning(String reasoning) {
      this.reasoning = reasoning;
      return this;
    }

    public Builder withEmittedDirective(JsonNode emittedDirective) {
      this.emittedDirective = emittedDirective;
      return this;
    }

    public Builder withIteration(int iteration) {
      this.iteration = iteration;
      return this;
    }

    public Builder withDurationMs(Integer durationMs) {
      this.durationMs = durationMs;
      return this;
    }

    public DecisionLogWriteRequest build() {
      return new DecisionLogWriteRequest(
          traceId,
          parentDecisionId,
          scopeKind,
          scopeId,
          scale,
          triggeredBy,
          actorUserId,
          inputs,
          candidates,
          chosen,
          reasoning,
          emittedDirective,
          iteration,
          durationMs);
    }
  }
}
