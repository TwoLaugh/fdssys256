package com.example.mealprep.core.audit.domain.service.internal;

import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.exception.DecisionLogPayloadOversizedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Service-layer guard capping the serialised JSONB payload of a decision-log write. Not expressible
 * as a Jakarta annotation because the limit is on serialised <em>size</em>, which bean-validation
 * cannot see. Per lld/core.md §Validation.
 *
 * <p>The total serialised size of the variable-shape JSONB fields ({@code inputs} + {@code
 * candidates} + {@code chosen} + {@code emittedDirective}) must be ≤ {@value #MAX_PAYLOAD_BYTES}
 * bytes (64 KB). The HLD predicts ~16 KB at week scale; 64 KB leaves headroom while preventing a
 * runaway prompt from dumping multi-MB candidate detail into the append-only audit log. Above the
 * cap → {@link DecisionLogPayloadOversizedException} (422).
 */
@Component
public class DecisionLogTokenBudgetGuard {

  /** Maximum serialised JSONB payload size, in bytes. 64 KB. */
  public static final long MAX_PAYLOAD_BYTES = 64L * 1024L;

  private final ObjectMapper objectMapper;

  public DecisionLogTokenBudgetGuard(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Assert the request's JSONB payload is within budget. Throws {@link
   * DecisionLogPayloadOversizedException} if the combined serialised size of {@code inputs}, {@code
   * candidates}, {@code chosen} and {@code emittedDirective} exceeds {@link #MAX_PAYLOAD_BYTES}.
   */
  public void assertWithinBudget(DecisionLogWriteRequest request) {
    long total =
        serializedSize(request.inputs())
            + serializedSize(request.candidates())
            + serializedSize(request.chosen())
            + serializedSize(request.emittedDirective());
    if (total > MAX_PAYLOAD_BYTES) {
      throw new DecisionLogPayloadOversizedException(total, MAX_PAYLOAD_BYTES);
    }
  }

  private long serializedSize(JsonNode node) {
    if (node == null || node.isNull()) {
      return 0L;
    }
    try {
      return objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
    } catch (JsonProcessingException ex) {
      // A JsonNode is always serialisable; treat any failure as a malformed payload rather than
      // letting it through unguarded.
      throw new DecisionLogPayloadOversizedException(Long.MAX_VALUE, MAX_PAYLOAD_BYTES);
    }
  }
}
