package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.provisions.spi.internal.ProvisionsFeedbackReverterImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProvisionsFeedbackReverterImpl} — provisions revert is log-only per the
 * LLD's immutability rule (feedback-01h §9-11). The reverter fabricates no inverse write and must
 * never throw, for every implemented action and for missing/absent payloads.
 */
class ProvisionsFeedbackReverterTest {

  private final ProvisionsFeedbackReverterImpl reverter = new ProvisionsFeedbackReverterImpl();

  @Test
  void revert_removeEquipment_isLogOnlyAndDoesNotThrow() {
    assertThatCode(() -> reverter.revert(ctx("REMOVE_EQUIPMENT"))).doesNotThrowAnyException();
  }

  @Test
  void revert_markDepleted_isLogOnlyAndDoesNotThrow() {
    assertThatCode(() -> reverter.revert(ctx("MARK_DEPLETED"))).doesNotThrowAnyException();
  }

  @Test
  void revert_unknownAction_isLogOnlyAndDoesNotThrow() {
    assertThatCode(() -> reverter.revert(ctx("ADJUST_BUDGET"))).doesNotThrowAnyException();
  }

  @Test
  void revert_nullPayload_isLogOnlyAndDoesNotThrow() {
    RevertContext ctx =
        new RevertContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Destination.PROVISIONS,
            null,
            null);
    assertThatCode(() -> reverter.revert(ctx)).doesNotThrowAnyException();
  }

  private static RevertContext ctx(String provisionsAction) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("provisionsAction", provisionsAction);
    return new RevertContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Destination.PROVISIONS,
        payload,
        JsonNodeFactory.instance.objectNode());
  }
}
