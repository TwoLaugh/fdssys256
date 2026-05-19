package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * All-args-constructor -&gt; getter round-trip for the append-only {@link DecisionLog} entity. PIT
 * flagged several getters (scale, candidates, chosen, emittedDirective, iteration) as untested
 * "replaced return value" survivors. Each field is given a distinct non-null sentinel so a
 * nulled/zeroed getter fails the assertion. {@code createdAt} is intentionally not asserted: it is
 * a Hibernate {@code @CreationTimestamp} populated only on persist, never by the constructor, so a
 * pure-unit assertion cannot exercise it (covered by the persistence IT instead).
 */
class DecisionLogEntityRoundTripTest {

  @Test
  void all_args_constructor_round_trips_every_getter() {
    UUID decisionId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UUID parentDecisionId = UUID.randomUUID();
    UUID scopeId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    JsonNode inputs = JsonNodeFactory.instance.objectNode().put("in", 1);
    JsonNode candidates = JsonNodeFactory.instance.arrayNode().add("c1");
    JsonNode chosen = JsonNodeFactory.instance.objectNode().put("pick", "c1");
    JsonNode emittedDirective = JsonNodeFactory.instance.objectNode().put("dir", "go");

    DecisionLog log =
        new DecisionLog(
            decisionId,
            traceId,
            parentDecisionId,
            "RECIPE",
            scopeId,
            DecisionLogScale.RECIPE,
            "feedback-listener",
            actorUserId,
            inputs,
            candidates,
            chosen,
            "chose c1 for lowest sodium",
            emittedDirective,
            4,
            987);

    assertThat(log.getDecisionId()).isEqualTo(decisionId);
    assertThat(log.getTraceId()).isEqualTo(traceId);
    assertThat(log.getParentDecisionId()).isEqualTo(parentDecisionId);
    assertThat(log.getScopeKind()).isEqualTo("RECIPE");
    assertThat(log.getScopeId()).isEqualTo(scopeId);
    assertThat(log.getScale()).isEqualTo(DecisionLogScale.RECIPE);
    assertThat(log.getTriggeredBy()).isEqualTo("feedback-listener");
    assertThat(log.getActorUserId()).isEqualTo(actorUserId);
    assertThat(log.getInputs()).isSameAs(inputs);
    assertThat(log.getCandidates()).isSameAs(candidates);
    assertThat(log.getChosen()).isSameAs(chosen);
    assertThat(log.getReasoning()).isEqualTo("chose c1 for lowest sodium");
    assertThat(log.getEmittedDirective()).isSameAs(emittedDirective);
    assertThat(log.getIteration()).isEqualTo(4);
    assertThat(log.getDurationMs()).isEqualTo(987);
  }

  @Test
  void nullable_fields_are_carried_as_null_when_omitted() {
    DecisionLog log =
        new DecisionLog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "WEEK",
            UUID.randomUUID(),
            DecisionLogScale.WEEK,
            "scheduler",
            null,
            JsonNodeFactory.instance.objectNode(),
            null,
            null,
            null,
            null,
            0,
            null);

    assertThat(log.getParentDecisionId()).isNull();
    assertThat(log.getActorUserId()).isNull();
    assertThat(log.getCandidates()).isNull();
    assertThat(log.getChosen()).isNull();
    assertThat(log.getReasoning()).isNull();
    assertThat(log.getEmittedDirective()).isNull();
    assertThat(log.getDurationMs()).isNull();
    assertThat(log.getIteration()).isZero();
    assertThat(log.getScale()).isEqualTo(DecisionLogScale.WEEK);
  }
}
