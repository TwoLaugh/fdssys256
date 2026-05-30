package com.example.mealprep.recipe.extraction;

import com.example.mealprep.recipe.extraction.internal.ExtractionValidator;
import com.example.mealprep.recipe.extraction.internal.JsonLdExtractionLayer;
import com.example.mealprep.recipe.extraction.internal.MicrodataExtractionLayer;
import com.example.mealprep.recipe.extraction.internal.PerSiteExtractorRegistry;
import com.example.mealprep.recipe.extraction.internal.RecipeExtractionServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Factory for a default {@link RecipeExtractionService} wired from the standard layer beans with an
 * empty per-site registry — the same composition Spring assembles in production.
 *
 * <p>Lets callers that aren't in a Spring context (unit tests; the convenience constructors of the
 * two consumer adapters) obtain a fully-wired service without reaching into the {@code
 * recipe.extraction.internal} package directly. This keeps the layer implementations encapsulated
 * behind the public {@code recipe.extraction} surface.
 */
public final class RecipeExtractionServices {

  private RecipeExtractionServices() {}

  /** A default service: JSON-LD + microdata layers, empty per-site registry, standard validator. */
  public static RecipeExtractionService defaultService(ObjectMapper objectMapper) {
    return new RecipeExtractionServiceImpl(
        new JsonLdExtractionLayer(objectMapper),
        new MicrodataExtractionLayer(),
        new PerSiteExtractorRegistry(List.of()),
        new ExtractionValidator());
  }
}
