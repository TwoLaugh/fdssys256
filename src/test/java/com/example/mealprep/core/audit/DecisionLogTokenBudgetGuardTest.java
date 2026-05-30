package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.service.internal.DecisionLogTokenBudgetGuard;
import com.example.mealprep.core.exception.DecisionLogPayloadOversizedException;
import com.example.mealprep.core.testdata.DecisionLogTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the 64 KB decision-log payload cap. Three fixtures per the LLD test plan: small
 * (well under), near-budget (just under), and oversized (just over). The guard sums the serialised
 * UTF-8 size of inputs + candidates + chosen + emittedDirective.
 */
class DecisionLogTokenBudgetGuardTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DecisionLogTokenBudgetGuard guard = new DecisionLogTokenBudgetGuard(objectMapper);

  @Test
  void smallPayload_passes() {
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder().withInputs(DecisionLogTestData.jsonObject("k", "v")).build();

    assertThatCode(() -> guard.assertWithinBudget(req)).doesNotThrowAnyException();
  }

  @Test
  void nullPayloadFields_pass() {
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder()
            .withInputs(null)
            .withCandidates(null)
            .withChosen(null)
            .withEmittedDirective(null)
            .build();

    assertThatCode(() -> guard.assertWithinBudget(req)).doesNotThrowAnyException();
  }

  @Test
  void nearBudgetPayload_passes() {
    // candidates array of strings whose serialised size is just under the 64 KB cap.
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder().withCandidates(stringPayloadOfBytes(60_000)).build();

    assertThatCode(() -> guard.assertWithinBudget(req)).doesNotThrowAnyException();
  }

  @Test
  void oversizedPayload_throws() {
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder().withCandidates(stringPayloadOfBytes(70_000)).build();

    assertThatThrownBy(() -> guard.assertWithinBudget(req))
        .isInstanceOf(DecisionLogPayloadOversizedException.class)
        .satisfies(
            ex -> {
              DecisionLogPayloadOversizedException e = (DecisionLogPayloadOversizedException) ex;
              assertThat(e.getMaxBytes()).isEqualTo(DecisionLogTokenBudgetGuard.MAX_PAYLOAD_BYTES);
              assertThat(e.getActualBytes())
                  .isGreaterThan(DecisionLogTokenBudgetGuard.MAX_PAYLOAD_BYTES);
            });
  }

  @Test
  void combinedFields_summedAcrossPayload_throwWhenTotalExceedsCap() {
    // Each field is individually under the cap, but together they exceed it.
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder()
            .withInputs(objectPayloadOfBytes(40_000))
            .withCandidates(stringPayloadOfBytes(40_000))
            .build();

    assertThatThrownBy(() -> guard.assertWithinBudget(req))
        .isInstanceOf(DecisionLogPayloadOversizedException.class);
  }

  /** An array node holding a single string of roughly {@code targetBytes} characters. */
  private static ArrayNode stringPayloadOfBytes(int targetBytes) {
    ArrayNode arr = JsonNodeFactory.instance.arrayNode();
    arr.add("a".repeat(targetBytes));
    return arr;
  }

  private static ObjectNode objectPayloadOfBytes(int targetBytes) {
    ObjectNode obj = JsonNodeFactory.instance.objectNode();
    obj.put("blob", "b".repeat(targetBytes));
    return obj;
  }
}
