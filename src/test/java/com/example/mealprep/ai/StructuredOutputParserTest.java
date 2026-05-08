package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.domain.service.internal.StructuredOutputParser;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StructuredOutputParserTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final StructuredOutputParser parser = new StructuredOutputParser(objectMapper);

  record Sample(String name, int age) {}

  private JsonNode schema(String json) throws Exception {
    return objectMapper.readTree(json);
  }

  @Test
  void parsesValidPayload() throws Exception {
    JsonNode schema =
        schema(
            "{\"type\":\"object\",\"required\":[\"name\",\"age\"],"
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}},"
                + "\"additionalProperties\":false}");
    Sample sample = parser.parse("{\"name\":\"alice\",\"age\":30}", schema, Sample.class);
    assertThat(sample.name()).isEqualTo("alice");
    assertThat(sample.age()).isEqualTo(30);
  }

  @Test
  void rejectsMissingRequiredField() throws Exception {
    JsonNode schema =
        schema(
            "{\"type\":\"object\",\"required\":[\"name\",\"age\"],"
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}");
    assertThatThrownBy(() -> parser.parse("{\"name\":\"alice\"}", schema, Sample.class))
        .isInstanceOf(AiInvalidResponseException.class)
        .hasMessageContaining("schema validation");
  }

  @Test
  void rejectsExtraField_whenSchemaForbids() throws Exception {
    JsonNode schema =
        schema(
            "{\"type\":\"object\",\"required\":[\"name\",\"age\"],"
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}},"
                + "\"additionalProperties\":false}");
    assertThatThrownBy(
            () ->
                parser.parse(
                    "{\"name\":\"alice\",\"age\":30,\"unexpected\":\"x\"}", schema, Sample.class))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void rejectsWrongType() throws Exception {
    JsonNode schema =
        schema(
            "{\"type\":\"object\",\"required\":[\"name\",\"age\"],"
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}");
    assertThatThrownBy(
            () -> parser.parse("{\"name\":\"alice\",\"age\":\"oldish\"}", schema, Sample.class))
        .isInstanceOf(AiInvalidResponseException.class);
  }

  @Test
  void bypassesSchemaValidationWhenSchemaIsNull() {
    Sample sample = parser.parse("{\"name\":\"bob\",\"age\":25}", null, Sample.class);
    assertThat(sample.name()).isEqualTo("bob");
  }

  @Test
  void rejectsInvalidJson() {
    assertThatThrownBy(() -> parser.parse("not-json", null, Sample.class))
        .isInstanceOf(AiInvalidResponseException.class)
        .hasMessageContaining("not valid JSON");
  }

  @Test
  void rejectsNullBody() {
    assertThatThrownBy(() -> parser.parse((String) null, null, Sample.class))
        .isInstanceOf(AiInvalidResponseException.class);
  }
}
