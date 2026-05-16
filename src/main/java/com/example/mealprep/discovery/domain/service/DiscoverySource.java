package com.example.mealprep.discovery.domain.service;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Per-source plugin SPI. Concrete implementations live exclusively in {@code
 * com.example.mealprep.discovery.source..} (enforced by {@code DiscoveryBoundaryTest}); the package
 * is empty in 01c — the first impls land in 01e (curated reference + Google CSE adapter).
 *
 * <p>Two-stage contract per LLD lines 375-397: {@link #search} produces lightweight candidate URLs
 * (no full-page fetches); {@link #fetchRecipe} performs the heavy extraction work after the runner
 * has passed politeness gates (robots.txt + per-source rate limiter).
 *
 * <p>NOT to be confused with {@link com.example.mealprep.discovery.domain.entity.DiscoverySource} —
 * the JPA entity carries the DB row (rpm, robots policy, failure streak); this SPI carries the code
 * that knows how to talk to the source.
 */
public interface DiscoverySource {

  /** Stable key matching {@code discovery_sources.source_key}. */
  String key();

  DiscoverySourceKind kind();

  /**
   * Produce candidate URLs for the constraint set. Cheap — does not fetch full pages.
   * Implementations honour {@code query.maxResults()} to bound per-source dominance.
   *
   * @throws DiscoverySourceUnavailableException on permanent source-level failure.
   */
  List<DiscoveryCandidate> search(DiscoveryQuery query);

  /**
   * Fetch the full page and produce a structured {@link ParsedRecipe}. The runner invokes only
   * after robots.txt and rate-limit checks pass. The HTML extraction strategy (microdata / JSON-LD
   * / site templates / AI fallback) is the source's concern.
   *
   * @throws ExtractionFailedException when extraction cannot produce a coherent recipe.
   */
  ParsedRecipe fetchRecipe(DiscoveryCandidate candidate);

  /**
   * Robots.txt URI. Empty for sources with their own API — the runner skips the robots check
   * entirely for those.
   */
  default Optional<URI> robotsTxtUri() {
    return Optional.empty();
  }
}
