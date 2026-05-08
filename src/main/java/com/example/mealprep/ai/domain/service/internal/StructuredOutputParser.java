package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Validates a model response against a JSON Schema and deserialises into the target type.
 *
 * <p>Schema mismatch (extra/missing fields, wrong types) raises {@link AiInvalidResponseException}.
 * Free-text responses bypass validation when the calling task supplies a {@code null} schema.
 */
@Component
public class StructuredOutputParser {

  private final ObjectMapper objectMapper;
  private final JsonSchemaFactory schemaFactory =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  public StructuredOutputParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public <T> T parse(String json, JsonNode schemaNode, Class<T> type) {
    if (json == null) {
      throw new AiInvalidResponseException("Response body was null");
    }
    JsonNode parsed;
    try {
      parsed = objectMapper.readTree(json);
    } catch (JsonProcessingException ex) {
      throw new AiInvalidResponseException("Response was not valid JSON", ex);
    }
    return parse(parsed, schemaNode, type);
  }

  public <T> T parse(JsonNode json, JsonNode schemaNode, Class<T> type) {
    if (json == null) {
      throw new AiInvalidResponseException("Response body was null");
    }
    if (schemaNode != null) {
      JsonSchema schema = schemaFactory.getSchema(schemaNode);
      Set<ValidationMessage> messages = schema.validate(json);
      if (!messages.isEmpty()) {
        String detail =
            messages.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
        throw new AiInvalidResponseException("Response failed schema validation: " + detail);
      }
    }
    try {
      return objectMapper.treeToValue(json, type);
    } catch (JsonProcessingException ex) {
      throw new AiInvalidResponseException(
          "Failed to deserialise response into " + type.getSimpleName(), ex);
    }
  }
}
