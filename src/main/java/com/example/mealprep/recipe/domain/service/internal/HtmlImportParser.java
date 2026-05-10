package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Deterministic v1 HTML → recipe extractor. Three strategies tried in order:
 *
 * <ol>
 *   <li><b>JSON-LD</b> — {@code <script type="application/ld+json">} blocks containing a {@code
 *       @type: "Recipe"} object (or "Recipe" within an array of types).
 *   <li><b>Microdata</b> — schema.org Recipe markup via {@code itemtype="*schema.org/Recipe"}.
 *   <li><b>Common selectors</b> — {@code h1.recipe-title}, {@code .ingredients li}, {@code .method
 *       li}.
 * </ol>
 *
 * If at least {@code name + 1 ingredient + 1 method step} cannot be assembled, throws {@link
 * RecipeImportFailureException} with {@code failureReason = "no_extractor_matched"}.
 */
@Component
public class HtmlImportParser {

  private final ObjectMapper objectMapper;

  public HtmlImportParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ParsedRecipe parse(String html, String url) {
    Document doc = Jsoup.parse(html, url);

    Optional<ParsedRecipe> jsonLd = tryJsonLd(doc);
    if (jsonLd.isPresent()) {
      return jsonLd.get().withExtractionMethod("json_ld");
    }
    Optional<ParsedRecipe> microdata = tryMicrodata(doc);
    if (microdata.isPresent()) {
      return microdata.get().withExtractionMethod("microdata");
    }
    Optional<ParsedRecipe> selectors = tryCommonSelectors(doc);
    if (selectors.isPresent()) {
      return selectors.get().withExtractionMethod("common_selectors");
    }
    throw new RecipeImportFailureException("no_extractor_matched");
  }

  // ---------------- JSON-LD ----------------

  private Optional<ParsedRecipe> tryJsonLd(Document doc) {
    Elements scripts = doc.select("script[type=application/ld+json]");
    for (Element script : scripts) {
      String json = script.data();
      if (json == null || json.isBlank()) {
        continue;
      }
      JsonNode root;
      try {
        root = objectMapper.readTree(json);
      } catch (Exception ignored) {
        continue;
      }
      Optional<JsonNode> recipeNode = findRecipeNode(root);
      if (recipeNode.isEmpty()) {
        continue;
      }
      ParsedRecipe parsed = parseFromJsonLdNode(recipeNode.get());
      if (parsed != null && isComplete(parsed)) {
        return Optional.of(parsed);
      }
    }
    return Optional.empty();
  }

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
      JsonNode type = node.get("@type");
      if (isRecipeType(type)) {
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

  private ParsedRecipe parseFromJsonLdNode(JsonNode r) {
    String name = textOrNull(r.get("name"));
    String description = textOrNull(r.get("description"));
    List<String> ingredients = stringArray(r.get("recipeIngredient"));
    List<String> methodSteps = parseInstructions(r.get("recipeInstructions"));
    Integer prep = isoDurationToMinutes(textOrNull(r.get("prepTime")));
    Integer cook = isoDurationToMinutes(textOrNull(r.get("cookTime")));
    Integer total = isoDurationToMinutes(textOrNull(r.get("totalTime")));
    Integer servings = parseServings(r.get("recipeYield"));
    String cuisine = textOrNull(r.get("recipeCuisine"));

    ObjectNode raw = objectMapper.createObjectNode();
    raw.set("jsonLd", r);

    return new ParsedRecipe(
        name,
        description,
        ingredients,
        methodSteps,
        prep,
        cook,
        total,
        servings,
        cuisine,
        null,
        raw);
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

  private Integer parseServings(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isInt() || node.isLong()) {
      return node.asInt();
    }
    if (node.isTextual()) {
      String s = node.asText().trim();
      try {
        return Integer.parseInt(s.split("\\s+")[0]);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    if (node.isArray() && !node.isEmpty()) {
      return parseServings(node.get(0));
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
      Duration d = Duration.parse(iso);
      return Math.toIntExact(d.toMinutes());
    } catch (DateTimeParseException | ArithmeticException ignored) {
      return null;
    }
  }

  // ---------------- Microdata ----------------

  private Optional<ParsedRecipe> tryMicrodata(Document doc) {
    Element root = doc.selectFirst("[itemtype*=schema.org/Recipe]");
    if (root == null) {
      return Optional.empty();
    }
    String name = textOf(root.selectFirst("[itemprop=name]"));
    String description = textOf(root.selectFirst("[itemprop=description]"));
    List<String> ingredients = textsOf(root.select("[itemprop=recipeIngredient]"));
    if (ingredients.isEmpty()) {
      ingredients = textsOf(root.select("[itemprop=ingredients]"));
    }
    List<String> methodSteps = textsOf(root.select("[itemprop=recipeInstructions]"));
    String cuisine = textOf(root.selectFirst("[itemprop=recipeCuisine]"));

    ObjectNode raw = objectMapper.createObjectNode();
    raw.put("microdataHtml", root.outerHtml());

    ParsedRecipe parsed =
        new ParsedRecipe(
            name,
            description,
            ingredients,
            methodSteps,
            null,
            null,
            null,
            null,
            cuisine,
            null,
            raw);
    return isComplete(parsed) ? Optional.of(parsed) : Optional.empty();
  }

  // ---------------- Common selectors ----------------

  private Optional<ParsedRecipe> tryCommonSelectors(Document doc) {
    String name = textOf(doc.selectFirst("h1.recipe-title"));
    if (name == null) {
      name = textOf(doc.selectFirst("h1"));
    }
    List<String> ingredients = textsOf(doc.select(".ingredients li"));
    List<String> methodSteps = textsOf(doc.select(".method li"));
    if (methodSteps.isEmpty()) {
      methodSteps = textsOf(doc.select(".instructions li"));
    }

    ObjectNode raw = objectMapper.createObjectNode();
    raw.put("titleSourceText", name == null ? "" : name);

    ParsedRecipe parsed =
        new ParsedRecipe(
            name, null, ingredients, methodSteps, null, null, null, null, null, null, raw);
    return isComplete(parsed) ? Optional.of(parsed) : Optional.empty();
  }

  // ---------------- Helpers ----------------

  private static String textOf(Element e) {
    if (e == null) {
      return null;
    }
    String text = e.text().trim();
    return text.isEmpty() ? null : text;
  }

  private static List<String> textsOf(Elements elements) {
    List<String> out = new ArrayList<>();
    if (elements == null) {
      return out;
    }
    for (Element e : elements) {
      String text = e.text().trim();
      if (!text.isEmpty()) {
        out.add(text);
      }
    }
    return out;
  }

  private static boolean isComplete(ParsedRecipe parsed) {
    return parsed != null
        && parsed.name() != null
        && !parsed.name().isBlank()
        && parsed.ingredientLines() != null
        && !parsed.ingredientLines().isEmpty()
        && parsed.methodSteps() != null
        && !parsed.methodSteps().isEmpty();
  }

  /** Strategy-agnostic parsed-recipe shape returned by every extractor. */
  public record ParsedRecipe(
      String name,
      String description,
      List<String> ingredientLines,
      List<String> methodSteps,
      Integer prepMinutes,
      Integer cookMinutes,
      Integer totalMinutes,
      Integer servings,
      String cuisine,
      String extractionMethod,
      JsonNode rawPayload) {

    public ParsedRecipe withExtractionMethod(String m) {
      return new ParsedRecipe(
          name,
          description,
          ingredientLines,
          methodSteps,
          prepMinutes,
          cookMinutes,
          totalMinutes,
          servings,
          cuisine,
          m,
          rawPayload);
    }
  }
}
