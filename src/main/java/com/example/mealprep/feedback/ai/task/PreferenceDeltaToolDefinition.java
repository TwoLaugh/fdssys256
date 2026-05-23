package com.example.mealprep.feedback.ai.task;

import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.feedback.ai.dto.TasteProfileDeltaResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Static {@link ToolDefinition} for {@code PreferenceTasteProfileDeltaTask} (preference-01g). The
 * JSON schema describes the {@link TasteProfileDeltaResponse} shape — a {@code deltas} array of the
 * eight {@code AiTasteProfileDelta} permits (discriminated on {@code type}), plus {@code
 * overallReasoning} and {@code warnings}.
 *
 * <p>Mirrors {@code feedback.domain.service.internal.ToolDefinitions}: the project does not depend
 * on {@code victools/jsonschema-generator}, so the schema is hand-built once at class load against
 * the same field set the records declare. If a delta record grows a field, this method grows with
 * it — a single-class change.
 */
public final class PreferenceDeltaToolDefinition {

  /** Tool name handed to Anthropic via the {@code tool_use} block. */
  public static final String TOOL_NAME = "propose_taste_profile_deltas";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDefinition DEFINITION = build();

  private PreferenceDeltaToolDefinition() {}

  /** Tool def for {@link com.example.mealprep.ai.spi.TaskType#PREFERENCE_DELTA_UPDATE}. */
  public static ToolDefinition get() {
    return DEFINITION;
  }

  private static ToolDefinition build() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");

    // deltas: array of the eight delta permits, discriminated on "type".
    ObjectNode deltas = properties.putObject("deltas");
    deltas.put("type", "array");
    deltas.put("minItems", 0);
    deltas.put("maxItems", 50);
    ObjectNode item = deltas.putObject("items");
    item.put("type", "object");
    ObjectNode itemProps = item.putObject("properties");

    ObjectNode type = itemProps.putObject("type");
    type.put("type", "string");
    ArrayNode typeEnum = type.putArray("enum");
    typeEnum.add("Add");
    typeEnum.add("Remove");
    typeEnum.add("Update");
    typeEnum.add("Archive");
    typeEnum.add("RePromote");
    typeEnum.add("PromoteExperiment");
    typeEnum.add("DiscardExperiment");
    typeEnum.add("UpdateNotes");

    // Union of all op fields — the model populates the subset relevant to `type`.
    stringField(itemProps, "fieldPath");
    stringField(itemProps, "item");
    stringField(itemProps, "notes");
    stringField(itemProps, "newNotes");
    stringField(itemProps, "archiveReason");
    stringField(itemProps, "archivedItemKey");
    stringField(itemProps, "hypothesisId");
    stringField(itemProps, "evidenceFeedbackId");
    stringField(itemProps, "reasoning");

    ObjectNode confidence = itemProps.putObject("confidence");
    confidence.put("type", "string");
    ArrayNode confEnum = confidence.putArray("enum");
    confEnum.add("HIGH");
    confEnum.add("MEDIUM");
    confEnum.add("LOW");

    ArrayNode itemRequired = item.putArray("required");
    itemRequired.add("type");
    itemRequired.add("evidenceFeedbackId");
    itemRequired.add("reasoning");

    // overallReasoning
    stringField(properties, "overallReasoning");

    // warnings: array of strings
    ObjectNode warnings = properties.putObject("warnings");
    warnings.put("type", "array");
    warnings.putObject("items").put("type", "string");

    ArrayNode required = schema.putArray("required");
    required.add("deltas");
    required.add("overallReasoning");

    return new ToolDefinition(
        TOOL_NAME,
        "Propose a batch of well-formed, well-justified taste-profile delta operations refining the"
            + " user's profile from the supplied feedback. Empty deltas when no change is"
            + " warranted.",
        schema);
  }

  private static void stringField(ObjectNode parent, String name) {
    parent.putObject(name).put("type", "string");
  }
}
