package com.example.mealprep.discovery.source.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedIngredient;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedMethodStep;
import com.example.mealprep.discovery.api.dto.ParsedRecipe.ParsedRecipeMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal JSON-LD recipe extractor shared by every {@code discovery.source..} adapter (01e). Pure
 * code, no HTTP — the adapters fetch the HTML and hand it here. Parses {@code <script
 * type="application/ld+json">} blocks via jsoup + Jackson and maps schema.org {@code Recipe} fields
 * to {@link ParsedRecipe}.
 *
 * <p><b>Scope (01e, deliberately the recipe-01b first-layer subset):</b> JSON-LD only — no
 * h-recipe, no per-site selectors, no AI fallback. The full 5-layer {@code RecipeExtractionService}
 * from {@code recipe-extraction-pipeline.md} is a separate ticket sequence; both adapters migrate
 * to it (Pattern B) when it lands. The ~80 LOC + test duplication with recipe-01b's {@code
 * HtmlImportParser} is the accepted v1 cost (worth user review).
 *
 * <p>Every field read is defensive: missing/wrong-type nodes degrade to {@code null}, never throw.
 */
@Component
public class JsonLdRecipeExtractor {

  private static final Logger log = LoggerFactory.getLogger(JsonLdRecipeExtractor.class);
  private static final String EXTRACTION_METHOD = "json_ld";
  private static final BigDecimal CONFIDENCE = BigDecimal.valueOf(0.85);
  private static final Pattern FIRST_INT = Pattern.compile("(\\d+)");

  private final ObjectMapper objectMapper;

  public JsonLdRecipeExtractor(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Extract the first schema.org {@code Recipe} from the page's JSON-LD blocks.
   *
   * @return the mapped {@link ParsedRecipe}, or {@link Optional#empty()} when no block parses to a
   *     {@code Recipe} (caller throws {@code ExtractionFailedException}).
   */
  public Optional<ParsedRecipe> extract(String html, String url) {
    if (html == null || html.isBlank()) {
      return Optional.empty();
    }
    Document doc = Jsoup.parse(html, url);
    for (Element script : doc.select("script[type=application/ld+json]")) {
      Optional<ParsedRecipe> maybe = tryParse(script.data(), url);
      if (maybe.isPresent()) {
        return maybe;
      }
    }
    return Optional.empty();
  }

  private Optional<ParsedRecipe> tryParse(String json, String url) {
    if (json == null || json.isBlank()) {
      return Optional.empty();
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode recipeNode = findRecipeNode(root);
      if (recipeNode == null) {
        return Optional.empty();
      }
      return Optional.of(mapToParsed(recipeNode, url));
    } catch (Exception e) {
      log.debug("malformed JSON-LD block at {}: {}", url, e.toString());
      return Optional.empty();
    }
  }

  /** Handles {@code @type="Recipe"} | {@code ["Recipe", ...]} | {@code @graph:[{...Recipe...}]}. */
  private JsonNode findRecipeNode(JsonNode root) {
    if (root == null) {
      return null;
    }
    if (root.isArray()) {
      for (JsonNode el : root) {
        JsonNode r = findRecipeNode(el);
        if (r != null) {
          return r;
        }
      }
      return null;
    }
    if (root.has("@graph")) {
      for (JsonNode el : root.get("@graph")) {
        JsonNode r = findRecipeNode(el);
        if (r != null) {
          return r;
        }
      }
    }
    JsonNode typeNode = root.get("@type");
    if (typeNode == null) {
      return null;
    }
    if (typeNode.isTextual() && "Recipe".equalsIgnoreCase(typeNode.asText())) {
      return root;
    }
    if (typeNode.isArray()) {
      for (JsonNode t : typeNode) {
        if (t.isTextual() && "Recipe".equalsIgnoreCase(t.asText())) {
          return root;
        }
      }
    }
    return null;
  }

  private ParsedRecipe mapToParsed(JsonNode r, String url) {
    String name = textOrNull(r.get("name"));
    String description = textOrNull(r.get("description"));

    List<ParsedIngredient> ingredients = new ArrayList<>();
    for (String line : stringArray(r.get("recipeIngredient"))) {
      // Quantity/unit/preparation parsing is deferred (worth user review): v1 ships displayName
      // only. The ingredient_mapping_key MUST be non-null though (NOT-NULL column + nutrition
      // mapping), so derive the deterministic v1 fallback from the display line via the canonical
      // cross-module normaliser. stringArray() already trims + drops blanks, so `line` is
      // non-blank.
      ingredients.add(
          new ParsedIngredient(
              line, IngredientMappingKeys.normalise(line), null, null, null, false));
    }

    List<ParsedMethodStep> method = new ArrayList<>();
    int step = 1;
    for (String instruction : parseInstructions(r.get("recipeInstructions"))) {
      method.add(new ParsedMethodStep(step++, instruction, null));
    }

    Integer servings = parseYield(r.get("recipeYield"));
    Integer prep = isoToMinutes(textOrNull(r.get("prepTime")));
    Integer cook = isoToMinutes(textOrNull(r.get("cookTime")));
    Integer total = isoToMinutes(textOrNull(r.get("totalTime")));
    String cuisine = textOrNull(r.get("recipeCuisine"));
    // recipeCategory/keywords ignored in v1; nutrition discarded (LLD line 299).

    ParsedRecipeMetadata metadata =
        new ParsedRecipeMetadata(servings, prep, cook, total, List.of(), cuisine, List.of());

    return new ParsedRecipe(
        url, name, description, ingredients, method, metadata, EXTRACTION_METHOD, CONFIDENCE);
  }

  private List<String> parseInstructions(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    if (node.isTextual()) {
      for (String line : node.asText().split("\\r?\\n")) {
        String t = line.trim();
        if (!t.isEmpty()) {
          out.add(t);
        }
      }
      return out;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual()) {
          String t = item.asText().trim();
          if (!t.isEmpty()) {
            out.add(t);
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
      String t = node.asText().trim();
      if (!t.isEmpty()) {
        out.add(t);
      }
      return out;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        if (item.isTextual()) {
          String t = item.asText().trim();
          if (!t.isEmpty()) {
            out.add(t);
          }
        }
      }
    }
    return out;
  }

  /** schema.org {@code recipeYield} is wildly inconsistent — pick the first integer (any). */
  private Integer parseYield(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isInt() || node.isLong()) {
      return node.asInt();
    }
    if (node.isArray() && !node.isEmpty()) {
      return parseYield(node.get(0));
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
    String t = node.asText().trim();
    return t.isEmpty() ? null : t;
  }

  private static Integer isoToMinutes(String iso) {
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
