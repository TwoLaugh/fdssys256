package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.feedback.api.dto.ClassificationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Static {@link ToolDefinition} for the feedback classifier. Generated once at class load; the JSON
 * schema describes the {@link ClassificationResult} shape so the AI returns the structured-output
 * payload the dispatcher will Jackson-bind into a {@code ClassificationResult}.
 *
 * <p>The project does not depend on {@code victools/jsonschema-generator}; the schema is hand-built
 * here against the same field set the record declares. If the record grows new fields, this method
 * grows with it — a single-class change.
 */
public final class ToolDefinitions {

  /** Tool name handed to Anthropic via the {@code tool_use} block. */
  public static final String CLASSIFY_FEEDBACK_TOOL_NAME = "classify_feedback";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDefinition CLASSIFY_FEEDBACK = build();

  private ToolDefinitions() {}

  /** Tool def for {@link com.example.mealprep.ai.spi.TaskType#FEEDBACK_CLASSIFICATION}. */
  public static ToolDefinition classifyFeedback() {
    return CLASSIFY_FEEDBACK;
  }

  private static ToolDefinition build() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");

    ObjectNode properties = schema.putObject("properties");

    // classifications: array of ClassificationOutput, 0..4 items
    ObjectNode classifications = properties.putObject("classifications");
    classifications.put("type", "array");
    classifications.put("minItems", 0);
    classifications.put("maxItems", 4);
    ObjectNode item = classifications.putObject("items");
    item.put("type", "object");
    ObjectNode itemProps = item.putObject("properties");

    ObjectNode destination = itemProps.putObject("destination");
    destination.put("type", "string");
    ArrayNode destEnum = destination.putArray("enum");
    destEnum.add("RECIPE");
    destEnum.add("PREFERENCE");
    destEnum.add("NUTRITION");
    destEnum.add("PROVISIONS");

    ObjectNode confidence = itemProps.putObject("confidence");
    confidence.put("type", "number");
    confidence.put("minimum", 0.0);
    confidence.put("maximum", 1.0);

    ObjectNode extracted = itemProps.putObject("extractedFeedback");
    extracted.put("type", "string");
    extracted.put("minLength", 1);

    ObjectNode structured = itemProps.putObject("structuredPayload");
    structured.put("type", "object");

    ArrayNode itemRequired = item.putArray("required");
    itemRequired.add("destination");
    itemRequired.add("confidence");
    itemRequired.add("extractedFeedback");
    itemRequired.add("structuredPayload");

    // overallConfidence
    ObjectNode overall = properties.putObject("overallConfidence");
    overall.put("type", "number");
    overall.put("minimum", 0.0);
    overall.put("maximum", 1.0);

    // classifierNotes — optional
    ObjectNode notes = properties.putObject("classifierNotes");
    notes.put("type", "string");

    ArrayNode required = schema.putArray("required");
    required.add("classifications");
    required.add("overallConfidence");

    return new ToolDefinition(
        CLASSIFY_FEEDBACK_TOOL_NAME,
        "Classify free-text feedback into routing destinations with confidence scores.",
        schema);
  }
}
