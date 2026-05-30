package com.example.mealprep.recipe.extraction.internal;

import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.ParsedRecipe.ParsedIngredient;
import com.example.mealprep.recipe.extraction.ParsedRecipe.ParsedMethodStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Layer 1 — {@code schema.org/Recipe} JSON-LD extraction, the dominant format (~80% of major recipe
 * sites). Per {@code recipe-extraction-pipeline.md} §"Layer 1 — JSON-LD".
 *
 * <p>This is the shared engine that consolidates the two pre-refactor JSON-LD parsers (recipe's
 * {@code HtmlImportParser.tryJsonLd} and discovery's {@code JsonLdRecipeExtractor}) into one. It
 * parses every {@code <script type="application/ld+json">} block, finds the first object whose
 * {@code @type} is (or contains) {@code "Recipe"} — traversing top-level arrays and {@code @graph}
 * — and maps schema.org fields onto {@link ParsedRecipe}. Every field read is defensive: missing or
 * wrong-typed nodes degrade to {@code null} / empty, never throw. A malformed block is skipped so a
 * later valid block can win.
 */
@Component
public class JsonLdExtractionLayer {

  private static final Logger log = LoggerFactory.getLogger(JsonLdExtractionLayer.class);
  private static final Pattern FIRST_INT = Pattern.compile("(\\d+)");

  private final ObjectMapper objectMapper;

  public JsonLdExtractionLayer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Extract the first {@code schema.org/Recipe} from the document's JSON-LD blocks.
   *
   * @return the mapped recipe, or {@link Optional#empty()} when no block parses to a Recipe.
   */
  public Optional<ParsedRecipe> extract(Document doc, String url) {
    if (doc == null) {
      return Optional.empty();
    }
    for (Element script : doc.select("script[type=application/ld+json]")) {
      String json = script.data();
      if (json == null || json.isBlank()) {
        continue;
      }
      JsonNode root;
      try {
        root = objectMapper.readTree(json);
      } catch (Exception e) {
        log.debug("malformed JSON-LD block at {}: {}", url, e.toString());
        continue;
      }
      Optional<JsonNode> recipeNode = findRecipeNode(root);
      if (recipeNode.isPresent()) {
        return Optional.of(mapToParsed(recipeNode.get(), url));
      }
    }
    return Optional.empty();
  }

  /** Handles {@code @type="Recipe"} | {@code ["Recipe", ...]} | {@code @graph:[{...Recipe...}]}. */
  private Optional<JsonNode> findRecipeNode(JsonNode node) {
    if (node == null) {
      return Optional.empty();
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        Optional<JsonNode> found = findRecipeNode(child);
        if (found.isPresent()) {
          return found;
        }
      }
      return Optional.empty();
    }
    if (node.isObject()) {
      if (isRecipeType(node.get("@type"))) {
        return Optional.of(node);
      }
      JsonNode graph = node.get("@graph");
      if (graph != null) {
        return findRecipeNode(graph);
      }
    }
    return Optional.empty();
  }

  private boolean isRecipeType(JsonNode type) {
    if (type == null) {
      return false;
    }
    if (type.isTextual()) {
      return "Recipe".equalsIgnoreCase(type.asText());
    }
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (t.isTextual() && "Recipe".equalsIgnoreCase(t.asText())) {
          return true;
        }
      }
    }
    return false;
  }

  private ParsedRecipe mapToParsed(JsonNode r, String url) {
    String name = textOrNull(r.get("name"));
    String description = textOrNull(r.get("description"));

    List<ParsedIngredient> ingredients = new ArrayList<>();
    for (String line : stringArray(r.get("recipeIngredient"))) {
      ingredients.add(ParsedIngredient.ofLine(line));
    }

    List<ParsedMethodStep> methodSteps = new ArrayList<>();
    int step = 1;
    for (String instruction : parseInstructions(r.get("recipeInstructions"))) {
      methodSteps.add(new ParsedMethodStep(step++, instruction));
    }

    Integer prep = isoDurationToMinutes(textOrNull(r.get("prepTime")));
    Integer cook = isoDurationToMinutes(textOrNull(r.get("cookTime")));
    Integer total = isoDurationToMinutes(textOrNull(r.get("totalTime")));
    Integer servings = parseServings(r.get("recipeYield"));
    String cuisine = textOrNull(r.get("recipeCuisine"));

    return new ParsedRecipe(
        url,
        name,
        description,
        ingredients,
        methodSteps,
        prep,
        cook,
        total,
        servings,
        cuisine,
        null);
  }

  private List<String> parseInstructions(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    if (node.isTextual()) {
      String text = node.asText();
      if (!text.isBlank()) {
        for (String line : text.split("\\r?\\n")) {
          String trimmed = line.trim();
          if (!trimmed.isEmpty()) {
            out.add(trimmed);
          }
        }
      }
      return out;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual()) {
          String text = item.asText().trim();
          if (!text.isEmpty()) {
            out.add(text);
          }
        } else if (item.isObject()) {
          JsonNode text = item.get("text");
          if (text != null && text.isTextual() && !text.asText().isBlank()) {
            out.add(text.asText().trim());
          } else {
            JsonNode itemList = item.get("itemListElement");
            if (itemList != null) {
              out.addAll(parseInstructions(itemList));
            }
          }
        }
      }
    }
    return out;
  }

  private List<String> stringArray(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    if (node.isTextual()) {
      String text = node.asText().trim();
      if (!text.isEmpty()) {
        out.add(text);
      }
      return out;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual()) {
          String text = item.asText().trim();
          if (!text.isEmpty()) {
            out.add(text);
          }
        }
      }
    }
    return out;
  }

  /** schema.org {@code recipeYield} is wildly inconsistent — pick the first integer (any). */
  private Integer parseServings(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isInt() || node.isLong()) {
      return node.asInt();
    }
    if (node.isArray() && !node.isEmpty()) {
      return parseServings(node.get(0));
    }
    if (node.isTextual()) {
      Matcher m = FIRST_INT.matcher(node.asText());
      if (m.find()) {
        try {
          return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ignored) {
          return null;
        }
      }
    }
    return null;
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || !node.isTextual()) {
      return null;
    }
    String text = node.asText().trim();
    return text.isEmpty() ? null : text;
  }

  private static Integer isoDurationToMinutes(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Math.toIntExact(Duration.parse(iso).toMinutes());
    } catch (DateTimeParseException | ArithmeticException ignored) {
      return null;
    }
  }
}
