package com.example.mealprep.planner.domain.service.internal.stagec;

import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Hand-built JSON schema for {@link Phase2AugmentationResponse}. The project ships no {@code
 * jsonschema-generator} (verified absent — see {@code feedback/ToolDefinitions}), so the sealed
 * {@code Augmentation} hierarchy's {@code oneOf} discriminator is mirrored here against the same
 * field set the raw {@code AugmentationProposal} / {@code RefineDirectiveProposal} records declare.
 * Grows with the records — a single-class change.
 */
final class Phase2ToolDefinitions {

  /** Tool name handed to Anthropic via the {@code tool_use} block. */
  static final String PHASE2_TOOL_NAME = "phase2_augmentation";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDefinition PHASE2 = build();

  private Phase2ToolDefinitions() {}

  static ToolDefinition phase2Augmentation() {
    return PHASE2;
  }

  private static ToolDefinition build() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    ObjectNode augmentations = props.putObject("augmentations");
    augmentations.put("type", "array");
    augmentations.put("minItems", 0);
    augmentations.put("maxItems", 5);
    ObjectNode aug = augmentations.putObject("items");
    aug.put("type", "object");
    // oneOf discriminator on `type`: ADD_SNACK | INGREDIENT_SWAP | REPAIR.
    aug.putArray("oneOf")
        .add(augVariant("ADD_SNACK", "targetSlotId", "newRecipeId", "servings"))
        .add(augVariant("INGREDIENT_SWAP", "targetSlotId", "fromIngredientKey", "toIngredientKey"))
        .add(augVariant("REPAIR", "targetSlotId", "issue", "resolution"));

    ObjectNode directives = props.putObject("refineDirectives");
    directives.put("type", "array");
    directives.put("minItems", 0);
    directives.put("maxItems", 2);
    ObjectNode dir = directives.putObject("items");
    dir.put("type", "object");
    dir.putArray("oneOf")
        .add(dirVariant("SUBSTITUTE_INGREDIENT", "fromIngredientKey", "toIngredientKey"))
        .add(dirVariant("REDUCE_TIME", "currentTimeMin", "targetTimeMin"));

    schema.putArray("required").add("augmentations").add("refineDirectives");
    return new ToolDefinition(
        PHASE2_TOOL_NAME,
        "Up to 5 plan augmentations plus up to 2 refine-directives improving the chosen plan.",
        schema);
  }

  private static ObjectNode augVariant(String type, String f1, String f2, String f3) {
    ObjectNode v = MAPPER.createObjectNode();
    v.put("type", "object");
    ObjectNode p = v.putObject("properties");
    ObjectNode t = p.putObject("type");
    t.put("type", "string");
    t.putArray("enum").add(type);
    p.putObject(f1).put("type", "string");
    p.putObject(f2).put("type", "string");
    p.putObject(f3).put("type", "string");
    p.putObject("reasoning").put("type", "string");
    v.putArray("required").add("type");
    return v;
  }

  private static ObjectNode dirVariant(String type, String f1, String f2) {
    ObjectNode v = MAPPER.createObjectNode();
    v.put("type", "object");
    ObjectNode p = v.putObject("properties");
    ObjectNode t = p.putObject("type");
    t.put("type", "string");
    t.putArray("enum").add(type);
    p.putObject("targetSlotId").put("type", "string");
    p.putObject(f1).put("type", "string");
    p.putObject(f2).put("type", "string");
    p.putObject("reasoning").put("type", "string");
    v.putArray("required").add("type");
    return v;
  }
}
