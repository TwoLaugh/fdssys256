package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.domain.service.internal.ToolUseInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolUseInvokerTest {

  @Test
  void noOpInvocationReturnsNullAndDoesNotThrow() {
    ToolUseInvoker invoker = new ToolUseInvoker();
    JsonNode out =
        invoker.invoke("unknown_tool", JsonNodeFactory.instance.objectNode(), Map.of("k", "v"));
    assertThat(out).isNull();
  }

  @Test
  void registeredHandlerOverridesNoOp() {
    ToolUseInvoker invoker = new ToolUseInvoker();
    JsonNode response = JsonNodeFactory.instance.objectNode().put("ok", true);
    invoker.register("my_tool", (input, ctx) -> response);
    JsonNode out = invoker.invoke("my_tool", JsonNodeFactory.instance.objectNode(), Map.of());
    assertThat(out).isEqualTo(response);
  }

  @Test
  void registerReturnsPriorHandler() {
    ToolUseInvoker invoker = new ToolUseInvoker();
    invoker.register("a", (i, c) -> JsonNodeFactory.instance.textNode("v1"));
    var prior = invoker.register("a", (i, c) -> JsonNodeFactory.instance.textNode("v2"));
    assertThat(prior).isPresent();
    assertThat(invoker.invoke("a", null, null)).isEqualTo(JsonNodeFactory.instance.textNode("v2"));
  }

  @Test
  void registerRejectsBlankToolName() {
    ToolUseInvoker invoker = new ToolUseInvoker();
    assertThatThrownBy(() -> invoker.register(" ", (i, c) -> null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registerRejectsNullHandler() {
    ToolUseInvoker invoker = new ToolUseInvoker();
    assertThatThrownBy(() -> invoker.register("foo", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isRegisteredReflectsState() {
    ToolUseInvoker invoker = new ToolUseInvoker();
    assertThat(invoker.isRegistered(ToolUseInvoker.NO_OP_NAME)).isTrue();
    assertThat(invoker.isRegistered("never-registered")).isFalse();
    invoker.register("x", (i, c) -> null);
    assertThat(invoker.isRegistered("x")).isTrue();
  }
}
