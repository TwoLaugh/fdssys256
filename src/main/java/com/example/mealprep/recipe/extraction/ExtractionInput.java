package com.example.mealprep.recipe.extraction;

/**
 * Input to the shared {@link RecipeExtractionService}. Two modes per {@code
 * recipe-extraction-pipeline.md} §"Two input modes":
 *
 * <ul>
 *   <li>{@link FromHtml} — the caller already has the page markup (the recipe URL-import flow
 *       fetches via {@code UrlFetcher}; the discovery sources fetch via {@code
 *       DiscoveryHttpFetcher}). The extraction layers run against the supplied {@code html}; {@code
 *       sourceUrl} is carried for provenance and base-URI resolution.
 *   <li>{@link FromUrl} — the service is asked to fetch the page itself. v1 ships the layer stack
 *       only; server-side fetching with rate-limiting / robots lives on the per-consumer fetchers
 *       (recipe {@code UrlFetcher}, discovery {@code DiscoveryHttpFetcher}) so both keep their
 *       existing politeness wiring. The service therefore exposes {@link FromHtml} as the realised
 *       v1 path and {@code FromUrl} as the typed surface for callers that hand a pre-fetched body
 *       in.
 * </ul>
 *
 * <p>This is a pure data carrier — no Spring, no HTTP — so it can be unit-tested without a context.
 */
public sealed interface ExtractionInput permits ExtractionInput.FromUrl, ExtractionInput.FromHtml {

  /** The source URL — provenance, base-URI resolution, and (future) per-site routing. */
  String sourceUrl();

  /** Caller supplies pre-fetched markup alongside the source URL. The realised v1 path. */
  record FromHtml(String sourceUrl, String html) implements ExtractionInput {}

  /**
   * Caller hands only a URL; the service is responsible for fetching. Reserved typed surface — v1
   * routes both live consumers through {@link FromHtml} with their own fetchers.
   */
  record FromUrl(String sourceUrl) implements ExtractionInput {}
}
