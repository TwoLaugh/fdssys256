package com.example.mealprep.recipe.extraction.internal;

import com.example.mealprep.recipe.extraction.ParsedRecipe;
import com.example.mealprep.recipe.extraction.RecipeSiteExtractor;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Layer 3 — per-site extractor registry, per {@code recipe-extraction-pipeline.md} §"Layer 3".
 * Spring injects every {@link RecipeSiteExtractor} bean; this registry indexes them by {@link
 * RecipeSiteExtractor#domainPattern()} and offers two hooks the service calls:
 *
 * <ul>
 *   <li>{@link #preExtract} — runs before Layers 1-2; if a matching extractor returns a recipe, the
 *       earlier layers are skipped.
 *   <li>{@link #postProcess} — runs on a Layer 1/2 result so a matching extractor can clean known
 *       per-site issues.
 * </ul>
 *
 * <p>v1 ships <b>zero</b> registered extractors (LLD line 127) — the registry is then a no-op
 * pass-through, which is the steady state today.
 */
@Component
public class PerSiteExtractorRegistry {

  private final List<RecipeSiteExtractor> extractors;

  public PerSiteExtractorRegistry(List<RecipeSiteExtractor> extractors) {
    this.extractors = extractors == null ? List.of() : List.copyOf(extractors);
  }

  /** Pre-extraction hook. Returns the first matching extractor's non-empty result, if any. */
  public Optional<ParsedRecipe> preExtract(String url, String html) {
    for (RecipeSiteExtractor extractor : matching(url)) {
      Optional<ParsedRecipe> raw = extractor.extractRaw(url, html);
      if (raw.isPresent()) {
        return raw;
      }
    }
    return Optional.empty();
  }

  /**
   * Post-processing hook. Applies every matching extractor's {@code postProcess} in registry order.
   */
  public ParsedRecipe postProcess(ParsedRecipe extracted, String url, String html) {
    ParsedRecipe result = extracted;
    for (RecipeSiteExtractor extractor : matching(url)) {
      result = extractor.postProcess(result, url, html);
    }
    return result;
  }

  /** True when at least one registered extractor matches the URL — used to record provenance. */
  public boolean hasMatch(String url) {
    return !matching(url).isEmpty();
  }

  private List<RecipeSiteExtractor> matching(String url) {
    if (url == null) {
      return List.of();
    }
    return extractors.stream()
        .filter(e -> e.domainPattern() != null && url.contains(e.domainPattern()))
        .toList();
  }
}
