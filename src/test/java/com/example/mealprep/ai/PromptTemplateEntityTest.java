package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.entity.PromptTemplate;
import com.example.mealprep.ai.spi.ModelTier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Getter coverage for {@link PromptTemplate}. Baseline survivors/no-coverage on this entity were
 * {@code getId}, {@code getSourceFile}, {@code getNotes}, {@code getCreatedAt}, etc. — pinning
 * every accessor kills the NullReturnVals / EmptyObjectReturnVals mutants.
 */
class PromptTemplateEntityTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void everyGetter_returnsItsConstructorField() throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode tools = objectMapper.createObjectNode();
    tools.put("foo", "bar");
    PromptTemplate t =
        new PromptTemplate(
            id,
            "classify",
            5,
            ModelTier.HIGH,
            "system-prompt",
            "user-template",
            schema,
            tools,
            "test-notes",
            "prompts/foo.md",
            "deadbeef0123abcd");

    assertThat(t.getId()).isEqualTo(id);
    assertThat(t.getName()).isEqualTo("classify");
    assertThat(t.getVersion()).isEqualTo(5);
    assertThat(t.getModelTier()).isEqualTo(ModelTier.HIGH);
    assertThat(t.getSystemPrompt()).isEqualTo("system-prompt");
    assertThat(t.getUserPromptTemplate()).isEqualTo("user-template");
    assertThat(t.getOutputSchema()).isSameAs(schema);
    assertThat(t.getTools()).isSameAs(tools);
    assertThat(t.getNotes()).isEqualTo("test-notes");
    assertThat(t.getSourceFile()).isEqualTo("prompts/foo.md");
    assertThat(t.getSourceHash()).isEqualTo("deadbeef0123abcd");

    // createdAt is null until Hibernate populates it — set via reflection to verify the getter.
    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    Field f = PromptTemplate.class.getDeclaredField("createdAt");
    f.setAccessible(true);
    f.set(t, ts);
    assertThat(t.getCreatedAt()).isEqualTo(ts);
  }

  @Test
  void nullableFields_returnNull_throughGetters() {
    UUID id = UUID.randomUUID();
    PromptTemplate t =
        new PromptTemplate(id, "n", 1, ModelTier.CHEAP, "s", "u", null, null, null, "src", "h");
    assertThat(t.getOutputSchema()).isNull();
    assertThat(t.getTools()).isNull();
    assertThat(t.getNotes()).isNull();
    assertThat(t.getCreatedAt()).isNull();
  }
}
