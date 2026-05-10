package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.ChangeAction;
import com.example.mealprep.recipe.api.dto.IngredientChangeDto;
import com.example.mealprep.recipe.api.dto.MetadataChangeDto;
import com.example.mealprep.recipe.api.dto.MethodChangeDto;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.dto.TagChangeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads the persisted {@code change_diff} JSONB on a {@link
 * com.example.mealprep.recipe.domain.entity.RecipeVersion} row and projects it to the public {@link
 * RecipeDiffDto}. Pure structural mapping — no recompute.
 */
@Component
public class RecipeDiffMapper {

  /** Build the public diff DTO from a persisted JSON node + the two version ids. */
  public RecipeDiffDto fromJsonNode(UUID fromVersionId, UUID toVersionId, JsonNode changeDiff) {
    JsonNode root = changeDiff != null ? changeDiff : MissingNode.getInstance();
    return new RecipeDiffDto(
        fromVersionId,
        toVersionId,
        readIngredientChanges(root.path("ingredientChanges")),
        readMethodChanges(root.path("methodChanges")),
        readMetadataChanges(root.path("metadataChanges")),
        readTagChanges(root.path("tagChanges")));
  }

  private static List<IngredientChangeDto> readIngredientChanges(JsonNode array) {
    List<IngredientChangeDto> out = new ArrayList<>();
    if (array == null || !array.isArray()) {
      return out;
    }
    for (JsonNode entry : array) {
      ChangeAction action = parseAction(entry.path("action").asText(null));
      JsonNode from = nullableNode(entry.path("from"));
      JsonNode to = nullableNode(entry.path("to"));
      String fieldChanged =
          entry.has("fieldChanged") && !entry.path("fieldChanged").isNull()
              ? entry.path("fieldChanged").asText()
              : null;
      out.add(new IngredientChangeDto(action, from, to, fieldChanged));
    }
    return out;
  }

  private static List<MethodChangeDto> readMethodChanges(JsonNode array) {
    List<MethodChangeDto> out = new ArrayList<>();
    if (array == null || !array.isArray()) {
      return out;
    }
    for (JsonNode entry : array) {
      ChangeAction action = parseAction(entry.path("action").asText(null));
      int step = entry.path("step").asInt();
      String from =
          entry.has("from") && !entry.path("from").isNull() ? entry.path("from").asText() : null;
      String to = entry.has("to") && !entry.path("to").isNull() ? entry.path("to").asText() : null;
      out.add(new MethodChangeDto(action, step, from, to));
    }
    return out;
  }

  private static List<MetadataChangeDto> readMetadataChanges(JsonNode array) {
    List<MetadataChangeDto> out = new ArrayList<>();
    if (array == null || !array.isArray()) {
      return out;
    }
    for (JsonNode entry : array) {
      ChangeAction action = parseAction(entry.path("action").asText(null));
      String field = entry.path("field").asText(null);
      JsonNode from = nullableNode(entry.path("from"));
      JsonNode to = nullableNode(entry.path("to"));
      out.add(new MetadataChangeDto(action, field, from, to));
    }
    return out;
  }

  private static List<TagChangeDto> readTagChanges(JsonNode array) {
    List<TagChangeDto> out = new ArrayList<>();
    if (array == null || !array.isArray()) {
      return out;
    }
    for (JsonNode entry : array) {
      ChangeAction action = parseAction(entry.path("action").asText(null));
      String dimension = entry.path("dimension").asText(null);
      JsonNode from = nullableNode(entry.path("from"));
      JsonNode to = nullableNode(entry.path("to"));
      out.add(new TagChangeDto(action, dimension, from, to));
    }
    return out;
  }

  private static ChangeAction parseAction(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return ChangeAction.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static JsonNode nullableNode(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    return node;
  }
}
