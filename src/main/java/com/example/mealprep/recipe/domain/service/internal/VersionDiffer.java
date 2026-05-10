package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeTagsRequest;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeMethodStep;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Pure-logic differ for two recipe-version bodies. Builds the structured {@code change_diff} JSON
 * persisted on the new {@code RecipeVersion} row at write time; the diff endpoint reads back the
 * same JSON via {@link com.example.mealprep.recipe.api.mapper.RecipeDiffMapper}.
 *
 * <p>Diff rules — see ticket §VersionDiffer:
 *
 * <ul>
 *   <li>Ingredients matched by {@code (ingredientMappingKey, preparation)} pair; multiple differing
 *       fields emit one entry per {@code fieldChanged} alphabetically.
 *   <li>Method steps matched by {@code stepNumber}.
 *   <li>Metadata + tags compared field-by-field.
 * </ul>
 */
@Component
public class VersionDiffer {

  private final ObjectMapper objectMapper;

  public VersionDiffer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Build the structured diff between {@code parent} (existing version) and {@code requested}. */
  public ObjectNode diff(RecipeVersion parent, NewVersionInput requested) {
    ObjectNode out = objectMapper.createObjectNode();
    out.set("ingredientChanges", diffIngredients(parent.getIngredients(), requested.ingredients()));
    out.set("methodChanges", diffMethod(parent.getMethodSteps(), requested.method()));
    out.set("metadataChanges", diffMetadata(parent.getMetadata(), requested.metadata()));
    out.set("tagChanges", diffTags(parent.getTags(), requested.tags()));
    return out;
  }

  /** {@code true} iff every diff section is empty. Used to detect no-op manual edits. */
  public boolean isEmpty(JsonNode diff) {
    if (diff == null) {
      return true;
    }
    return sectionEmpty(diff, "ingredientChanges")
        && sectionEmpty(diff, "methodChanges")
        && sectionEmpty(diff, "metadataChanges")
        && sectionEmpty(diff, "tagChanges");
  }

  private static boolean sectionEmpty(JsonNode diff, String name) {
    JsonNode section = diff.path(name);
    return section.isMissingNode() || section.isNull() || section.size() == 0;
  }

  // ---------------- Ingredients ----------------

  private ArrayNode diffIngredients(
      List<RecipeIngredient> parent, List<CreateIngredientRequest> requested) {
    ArrayNode out = objectMapper.createArrayNode();
    Map<IngredientKey, RecipeIngredient> parentByKey = new LinkedHashMap<>();
    if (parent != null) {
      for (RecipeIngredient i : parent) {
        parentByKey.put(new IngredientKey(i.getIngredientMappingKey(), i.getPreparation()), i);
      }
    }
    Map<IngredientKey, CreateIngredientRequest> requestedByKey = new LinkedHashMap<>();
    if (requested != null) {
      for (CreateIngredientRequest r : requested) {
        requestedByKey.put(new IngredientKey(r.ingredientMappingKey(), r.preparation()), r);
      }
    }

    // ADDED: in requested, not in parent.
    for (Map.Entry<IngredientKey, CreateIngredientRequest> e : requestedByKey.entrySet()) {
      if (!parentByKey.containsKey(e.getKey())) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("action", "ADDED");
        entry.set("from", objectMapper.nullNode());
        entry.set("to", ingredientSnapshot(e.getValue()));
        out.add(entry);
      }
    }
    // REMOVED: in parent, not in requested.
    for (Map.Entry<IngredientKey, RecipeIngredient> e : parentByKey.entrySet()) {
      if (!requestedByKey.containsKey(e.getKey())) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("action", "REMOVED");
        entry.set("from", ingredientSnapshot(e.getValue()));
        entry.set("to", objectMapper.nullNode());
        out.add(entry);
      }
    }
    // MODIFIED: in both — compare per-field.
    for (Map.Entry<IngredientKey, RecipeIngredient> e : parentByKey.entrySet()) {
      CreateIngredientRequest req = requestedByKey.get(e.getKey());
      if (req == null) {
        continue;
      }
      RecipeIngredient existing = e.getValue();
      Set<String> changed = new TreeSet<>();
      if (!equalNullable(existing.getDisplayName(), req.displayName())) {
        changed.add("displayName");
      }
      if (existing.getLineOrder() != req.lineOrder()) {
        changed.add("lineOrder");
      }
      if (existing.getMappingConfidence() != null
          || (existing.getMappingConfidence() == null && false)) {
        // mappingConfidence is server-computed today; never compared against the request.
      }
      if (existing.isOptional() != Boolean.TRUE.equals(req.optional())) {
        changed.add("optional");
      }
      if (existing.isNeedsReview()) {
        // needsReview is server-driven; never present on a manual edit request.
      }
      if (!equalBigDecimal(existing.getQuantity(), req.quantity())) {
        changed.add("quantity");
      }
      if (!equalNullable(existing.getUnit(), req.unit())) {
        changed.add("unit");
      }
      // Note: ingredientMappingKey + preparation are part of the match key, so they cannot
      // differ here — a change to either of those fields shows up as REMOVED + ADDED.

      for (String field : changed) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("action", "MODIFIED");
        entry.set("from", ingredientSnapshot(existing));
        entry.set("to", ingredientSnapshot(req));
        entry.put("fieldChanged", field);
        out.add(entry);
      }
    }
    return out;
  }

  private ObjectNode ingredientSnapshot(RecipeIngredient i) {
    ObjectNode s = objectMapper.createObjectNode();
    s.put("ingredientMappingKey", i.getIngredientMappingKey());
    s.put("displayName", i.getDisplayName());
    if (i.getQuantity() != null) {
      s.put("quantity", i.getQuantity());
    } else {
      s.set("quantity", objectMapper.nullNode());
    }
    s.put("unit", i.getUnit());
    s.put("preparation", i.getPreparation());
    s.put("optional", i.isOptional());
    s.put("lineOrder", i.getLineOrder());
    return s;
  }

  private ObjectNode ingredientSnapshot(CreateIngredientRequest r) {
    ObjectNode s = objectMapper.createObjectNode();
    s.put("ingredientMappingKey", r.ingredientMappingKey());
    s.put("displayName", r.displayName());
    if (r.quantity() != null) {
      s.put("quantity", r.quantity());
    } else {
      s.set("quantity", objectMapper.nullNode());
    }
    s.put("unit", r.unit());
    s.put("preparation", r.preparation());
    s.put("optional", Boolean.TRUE.equals(r.optional()));
    s.put("lineOrder", r.lineOrder());
    return s;
  }

  private record IngredientKey(String mappingKey, String preparation) {}

  // ---------------- Method ----------------

  private ArrayNode diffMethod(
      List<RecipeMethodStep> parent, List<CreateMethodStepRequest> requested) {
    ArrayNode out = objectMapper.createArrayNode();
    Map<Integer, RecipeMethodStep> parentByStep = new HashMap<>();
    if (parent != null) {
      for (RecipeMethodStep s : parent) {
        parentByStep.put(s.getStepNumber(), s);
      }
    }
    Map<Integer, CreateMethodStepRequest> requestedByStep = new HashMap<>();
    if (requested != null) {
      for (CreateMethodStepRequest s : requested) {
        requestedByStep.put(s.stepNumber(), s);
      }
    }

    Set<Integer> allSteps = new TreeSet<>();
    allSteps.addAll(parentByStep.keySet());
    allSteps.addAll(requestedByStep.keySet());

    for (Integer stepNumber : allSteps) {
      RecipeMethodStep p = parentByStep.get(stepNumber);
      CreateMethodStepRequest r = requestedByStep.get(stepNumber);
      if (p == null) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("action", "ADDED");
        entry.put("step", stepNumber);
        entry.set("from", objectMapper.nullNode());
        entry.put("to", r.instruction());
        out.add(entry);
      } else if (r == null) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("action", "REMOVED");
        entry.put("step", stepNumber);
        entry.put("from", p.getInstruction());
        entry.set("to", objectMapper.nullNode());
        out.add(entry);
      } else {
        boolean instructionDiffers = !equalNullable(p.getInstruction(), r.instruction());
        boolean durationDiffers = !equalNullable(p.getDurationMinutes(), r.durationMinutes());
        if (instructionDiffers || durationDiffers) {
          ObjectNode entry = objectMapper.createObjectNode();
          entry.put("action", "MODIFIED");
          entry.put("step", stepNumber);
          entry.put("from", p.getInstruction());
          entry.put("to", r.instruction());
          out.add(entry);
        }
      }
    }
    return out;
  }

  // ---------------- Metadata ----------------

  private ArrayNode diffMetadata(RecipeMetadata parent, CreateRecipeMetadataRequest requested) {
    ArrayNode out = objectMapper.createArrayNode();
    if (parent == null && requested == null) {
      return out;
    }
    addMetadataChange(
        out,
        "servings",
        parent != null ? parent.getServings() : null,
        requested != null ? requested.servings() : null);
    addMetadataChange(
        out,
        "prepTimeMins",
        parent != null ? parent.getPrepTimeMins() : null,
        requested != null ? requested.prepTimeMins() : null);
    addMetadataChange(
        out,
        "cookTimeMins",
        parent != null ? parent.getCookTimeMins() : null,
        requested != null ? requested.cookTimeMins() : null);
    addMetadataChange(
        out,
        "totalTimeMins",
        parent != null ? parent.getTotalTimeMins() : null,
        requested != null ? requested.totalTimeMins() : null);
    addMetadataChange(
        out,
        "fridgeDays",
        parent != null ? parent.getFridgeDays() : null,
        requested != null ? requested.fridgeDays() : null);
    addMetadataChange(
        out,
        "freezerWeeks",
        parent != null ? parent.getFreezerWeeks() : null,
        requested != null ? requested.freezerWeeks() : null);
    addMetadataChange(
        out,
        "packable",
        parent != null ? parent.isPackable() : null,
        requested != null ? Boolean.TRUE.equals(requested.packable()) : null);
    addMetadataChange(
        out,
        "cuisine",
        parent != null ? parent.getCuisine() : null,
        requested != null ? requested.cuisine() : null);
    addMetadataListChange(
        out,
        "equipmentRequired",
        parent != null ? parent.getEquipmentRequired() : null,
        requested != null ? requested.equipmentRequired() : null);
    addMetadataListChange(
        out,
        "mealTypes",
        parent != null ? parent.getMealTypes() : null,
        requested != null ? requested.mealTypes() : null);
    return out;
  }

  private void addMetadataChange(ArrayNode out, String field, Object from, Object to) {
    if (Objects.equals(from, to)) {
      return;
    }
    ObjectNode entry = objectMapper.createObjectNode();
    entry.put("action", "MODIFIED");
    entry.put("field", field);
    entry.set("from", objectMapper.valueToTree(from));
    entry.set("to", objectMapper.valueToTree(to));
    out.add(entry);
  }

  private void addMetadataListChange(
      ArrayNode out, String field, List<String> from, List<String> to) {
    List<String> normFrom = from != null ? new ArrayList<>(from) : new ArrayList<>();
    List<String> normTo = to != null ? new ArrayList<>(to) : new ArrayList<>();
    if (Objects.equals(normFrom, normTo)) {
      return;
    }
    ObjectNode entry = objectMapper.createObjectNode();
    entry.put("action", "MODIFIED");
    entry.put("field", field);
    entry.set("from", objectMapper.valueToTree(normFrom));
    entry.set("to", objectMapper.valueToTree(normTo));
    out.add(entry);
  }

  // ---------------- Tags ----------------

  private ArrayNode diffTags(RecipeTags parent, CreateRecipeTagsRequest requested) {
    ArrayNode out = objectMapper.createArrayNode();
    addTagChange(
        out,
        "protein",
        parent != null ? parent.getProtein() : null,
        requested != null ? requested.protein() : null);
    addTagChange(
        out,
        "cookingMethod",
        parent != null ? parent.getCookingMethod() : null,
        requested != null ? requested.cookingMethod() : null);
    addTagChange(
        out,
        "complexity",
        parent != null && parent.getComplexity() != null ? parent.getComplexity().name() : null,
        requested != null && requested.complexity() != null ? requested.complexity().name() : null);
    addTagListChange(
        out,
        "flavourProfile",
        parent != null ? parent.getFlavourProfile() : null,
        requested != null ? requested.flavourProfile() : null);
    addTagListChange(
        out,
        "dietaryFlags",
        parent != null ? parent.getDietaryFlags() : null,
        requested != null ? requested.dietaryFlags() : null);
    return out;
  }

  private void addTagChange(ArrayNode out, String dimension, Object from, Object to) {
    if (Objects.equals(from, to)) {
      return;
    }
    ObjectNode entry = objectMapper.createObjectNode();
    entry.put("action", "MODIFIED");
    entry.put("dimension", dimension);
    entry.set("from", objectMapper.valueToTree(from));
    entry.set("to", objectMapper.valueToTree(to));
    out.add(entry);
  }

  private void addTagListChange(
      ArrayNode out, String dimension, List<String> from, List<String> to) {
    List<String> normFrom = from != null ? new ArrayList<>(from) : new ArrayList<>();
    List<String> normTo = to != null ? new ArrayList<>(to) : new ArrayList<>();
    if (Objects.equals(normFrom, normTo)) {
      return;
    }
    ObjectNode entry = objectMapper.createObjectNode();
    entry.put("action", "MODIFIED");
    entry.put("dimension", dimension);
    entry.set("from", objectMapper.valueToTree(normFrom));
    entry.set("to", objectMapper.valueToTree(normTo));
    out.add(entry);
  }

  // ---------------- Helpers ----------------

  private static boolean equalNullable(Object a, Object b) {
    return Objects.equals(a, b);
  }

  private static boolean equalBigDecimal(BigDecimal a, BigDecimal b) {
    if (a == null && b == null) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    return a.compareTo(b) == 0;
  }

  /** Comparator used by callers when emitting MODIFIED ingredient entries. */
  static final Comparator<String> ALPHA = Comparator.naturalOrder();
}
