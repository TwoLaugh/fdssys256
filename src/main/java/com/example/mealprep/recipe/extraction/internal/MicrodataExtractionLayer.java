package com.example.mealprep.recipe.extraction.internal;

import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.ParsedRecipe.ParsedIngredient;
import com.example.mealprep.recipe.extraction.ParsedRecipe.ParsedMethodStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Layer 2 — schema.org microdata (h-recipe-style) extraction plus the common-selector fallback, per
 * {@code recipe-extraction-pipeline.md} §"Layer 2 — h-recipe microformats". The legacy alternative
 * for older food blogs that publish no JSON-LD.
 *
 * <p>Consolidates the two non-JSON-LD strategies the recipe URL-import path shipped pre-refactor
 * ({@code HtmlImportParser.tryMicrodata} + {@code tryCommonSelectors}): first {@code
 * itemtype*=schema.org/Recipe} microdata, then the {@code h1.recipe-title} / {@code .ingredients
 * li} / {@code .method li} selectors. Returns empty when neither yields a usable recipe so the
 * stack falls through to the validator's hard-fail path.
 */
@Component
public class MicrodataExtractionLayer {

  /** The concrete sub-strategy that matched within Layer 2. */
  public static final String DETAIL_MICRODATA = "microdata";

  public static final String DETAIL_COMMON_SELECTORS = "common_selectors";

  /** A Layer-2 result plus the sub-strategy ({@code microdata} / {@code common_selectors}). */
  public record Result(ParsedRecipe recipe, String detail) {}

  /** Try microdata, then common selectors. First usable result wins. */
  public Optional<Result> extract(Document doc, String url) {
    if (doc == null) {
      return Optional.empty();
    }
    Optional<ParsedRecipe> microdata = tryMicrodata(doc, url);
    if (microdata.isPresent()) {
      return Optional.of(new Result(microdata.get(), DETAIL_MICRODATA));
    }
    Optional<ParsedRecipe> selectors = tryCommonSelectors(doc, url);
    return selectors.map(r -> new Result(r, DETAIL_COMMON_SELECTORS));
  }

  private Optional<ParsedRecipe> tryMicrodata(Document doc, String url) {
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

    return assemble(url, name, description, ingredients, methodSteps, cuisine);
  }

  private Optional<ParsedRecipe> tryCommonSelectors(Document doc, String url) {
    String name = textOf(doc.selectFirst("h1.recipe-title"));
    if (name == null) {
      name = textOf(doc.selectFirst("h1"));
    }
    List<String> ingredients = textsOf(doc.select(".ingredients li"));
    List<String> methodSteps = textsOf(doc.select(".method li"));
    if (methodSteps.isEmpty()) {
      methodSteps = textsOf(doc.select(".instructions li"));
    }
    return assemble(url, name, null, ingredients, methodSteps, null);
  }

  private Optional<ParsedRecipe> assemble(
      String url,
      String name,
      String description,
      List<String> ingredientLines,
      List<String> methodLines,
      String cuisine) {
    List<ParsedIngredient> ingredients = new ArrayList<>();
    for (String line : ingredientLines) {
      ingredients.add(ParsedIngredient.ofLine(line));
    }
    List<ParsedMethodStep> method = new ArrayList<>();
    int step = 1;
    for (String instruction : methodLines) {
      method.add(new ParsedMethodStep(step++, instruction));
    }
    ParsedRecipe parsed =
        new ParsedRecipe(
            url, name, description, ingredients, method, null, null, null, null, cuisine, null);
    return isUsable(parsed) ? Optional.of(parsed) : Optional.empty();
  }

  private static boolean isUsable(ParsedRecipe parsed) {
    return parsed.name() != null
        && !parsed.name().isBlank()
        && !parsed.ingredients().isEmpty()
        && !parsed.methodSteps().isEmpty();
  }

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
}
