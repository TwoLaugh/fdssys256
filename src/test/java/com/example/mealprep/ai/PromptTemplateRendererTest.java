package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.domain.service.internal.PromptTemplateRenderer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateRendererTest {

  private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

  @Test
  void substitutesSimplePlaceholder() {
    String result = renderer.render("Hello {{name}}!", Map.of("name", "world"));
    assertThat(result).isEqualTo("Hello world!");
  }

  @Test
  void substitutesMultiplePlaceholders() {
    String result =
        renderer.render(
            "[Task: {{task}}]\nUser: {{user}}", Map.of("task", "DEMO", "user", "alice"));
    assertThat(result).isEqualTo("[Task: DEMO]\nUser: alice");
  }

  @Test
  void allowsRepeatedPlaceholder() {
    String result = renderer.render("{{x}} and {{x}}", Map.of("x", "yo"));
    assertThat(result).isEqualTo("yo and yo");
  }

  @Test
  void allowsDottedAndUnderscoredVariableNames() {
    String result =
        renderer.render(
            "<{{ TASTE_PROFILE_JSON }}>{{ feedback.batch_id }}",
            Map.of("TASTE_PROFILE_JSON", "{}", "feedback.batch_id", "b-1"));
    assertThat(result).isEqualTo("<{}>b-1");
  }

  @Test
  void rejectsMissingVariable() {
    assertThatThrownBy(() -> renderer.render("Hi {{name}}", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing prompt variable 'name'");
  }

  @Test
  void rejectsNullValue() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("name", null);
    assertThatThrownBy(() -> renderer.render("Hi {{name}}", vars))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolved to null");
  }

  @Test
  void rejectsNullVariablesMapWhenPlaceholderPresent() {
    assertThatThrownBy(() -> renderer.render("Hi {{name}}", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing prompt variable 'name'");
  }

  @Test
  void allowsNullVariablesMapWhenNoPlaceholder() {
    String result = renderer.render("Just a string with no placeholders.", null);
    assertThat(result).isEqualTo("Just a string with no placeholders.");
  }

  @Test
  void valuesContainingDoubleBracesPassThroughUntouched() {
    // Double-substitution would re-render the template — explicitly avoided.
    String result = renderer.render("Wrap: {{value}}", Map.of("value", "{{nested}}"));
    assertThat(result).isEqualTo("Wrap: {{nested}}");
  }

  @Test
  void valueWithDollarSignAndBackslashIsLiteral() {
    String result = renderer.render("V: {{v}}", Map.of("v", "$1\\back"));
    assertThat(result).isEqualTo("V: $1\\back");
  }

  @Test
  void integerAndUuidValuesUseToString() {
    String result =
        renderer.render(
            "n={{n}} id={{id}}",
            Map.of(
                "n", 42, "id", java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")));
    assertThat(result).isEqualTo("n=42 id=00000000-0000-0000-0000-000000000001");
  }

  @Test
  void emptyTemplateReturnsEmpty() {
    assertThat(renderer.render("", Map.of())).isEmpty();
  }

  @Test
  void tolerantToWhitespaceAroundVariableName() {
    String result = renderer.render("X={{   key  }}", Map.of("key", "v"));
    assertThat(result).isEqualTo("X=v");
  }
}
