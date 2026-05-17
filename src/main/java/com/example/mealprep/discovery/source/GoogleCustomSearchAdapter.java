package com.example.mealprep.discovery.source;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryHttpFetcher;
import com.example.mealprep.discovery.config.GoogleCustomSearchConfig;
import com.example.mealprep.discovery.config.HttpFetchException;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.source.internal.GoogleCseDailyQuotaTracker;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Google Custom Search JSON API adapter — the v1 {@code SEARCH} source ({@code source_key =
 * google_cse}). Lives in {@code discovery.source..} (the LLD hard pocket). Per 01e ticket §
 * GoogleCustomSearchAdapter and {@code recipe-extraction-pipeline.md} lines 337-356.
 *
 * <p><b>No Spring Web imports here</b> ({@code springWebStaysInApi} ArchUnit rule): all HTTP goes
 * through {@link DiscoveryHttpFetcher} (a {@code discovery.config} bean) which surfaces only
 * JDK/Jackson types and the Spring-free {@link HttpFetchException}.
 *
 * <p><b>Pattern A (v1):</b> this adapter does double duty — SERP {@link #search} AND {@link
 * #fetchRecipe} extraction of the arbitrary sites the SERP returns, via the shared {@link
 * JsonLdRecipeExtractor}. Migrates to Pattern B (shared {@code RecipeExtractionService}) when the
 * recipe-extraction-pipeline tickets land. Worth user review.
 */
@Component
public class GoogleCustomSearchAdapter implements DiscoverySource {

  private static final Logger log = LoggerFactory.getLogger(GoogleCustomSearchAdapter.class);
  private static final String KEY = "google_cse";

  private final GoogleCustomSearchConfig config;
  private final GoogleCseDailyQuotaTracker quotaTracker;
  private final JsonLdRecipeExtractor jsonLdExtractor;
  private final DiscoveryHttpFetcher httpFetcher;

  public GoogleCustomSearchAdapter(
      GoogleCustomSearchConfig config,
      GoogleCseDailyQuotaTracker quotaTracker,
      JsonLdRecipeExtractor jsonLdExtractor,
      DiscoveryHttpFetcher httpFetcher) {
    this.config = config;
    this.quotaTracker = quotaTracker;
    this.jsonLdExtractor = jsonLdExtractor;
    this.httpFetcher = httpFetcher;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public DiscoverySourceKind kind() {
    return DiscoverySourceKind.SEARCH_API;
  }

  @Override
  public Optional<URI> robotsTxtUri() {
    // Google CSE is an API — the runner skips robots for sources with no robots URI (LLD line 396).
    return Optional.empty();
  }

  @Override
  public List<DiscoveryCandidate> search(DiscoveryQuery query) {
    if (quotaTracker.todaysCount() >= config.maxQueriesPerDay()) {
      throw new DiscoverySourceUnavailableException(KEY, "daily quota exhausted", null);
    }
    String q = buildQueryString(query.constraints());
    int num = Math.min(config.resultsPerQuery(), Math.max(1, query.maxResults()));

    Map<String, String> params = new LinkedHashMap<>();
    params.put("key", config.apiKey());
    params.put("cx", config.searchEngineId());
    params.put("q", q);
    params.put("num", String.valueOf(num));

    JsonNode response;
    try {
      response =
          httpFetcher.getJson(
              "https", "www.googleapis.com", "/customsearch/v1", params, config.userAgent());
      // Google bills the query whether or not we like the response — count after the call.
      quotaTracker.recordCall();
    } catch (HttpFetchException e) {
      if (e.statusCode() != null) {
        // Quota / billing exhausted, invalid key, 5xx — all permanent at the source level.
        quotaTracker.recordCall();
        throw new DiscoverySourceUnavailableException(KEY, e.getMessage(), e);
      }
      // Network error / timeout — no billable call landed; don't count.
      throw new DiscoverySourceUnavailableException(KEY, "network error", e);
    }
    return parseCandidates(response, num);
  }

  @Override
  public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
    String html;
    try {
      html = httpFetcher.getString(candidate.candidateUrl(), config.userAgent());
    } catch (HttpFetchException e) {
      throw new ExtractionFailedException(
          candidate.candidateUrl(), "fetch failed: " + e.getMessage());
    }
    return jsonLdExtractor
        .extract(html, candidate.candidateUrl())
        .orElseThrow(() -> new ExtractionFailedException(candidate.candidateUrl(), "no_jsonld"));
  }

  private List<DiscoveryCandidate> parseCandidates(JsonNode response, int num) {
    if (response == null || !response.has("items")) {
      return List.of();
    }
    List<DiscoveryCandidate> out = new ArrayList<>();
    int i = 0;
    for (JsonNode item : response.get("items")) {
      if (out.size() >= num) {
        break;
      }
      out.add(
          new DiscoveryCandidate(
              KEY,
              item.path("link").asText(null),
              item.path("title").asText(null),
              item.path("snippet").asText(null),
              Map.of(
                  "serpRank",
                  String.valueOf(i++),
                  "displayLink",
                  item.path("displayLink").asText(""))));
    }
    return out;
  }

  /**
   * Deterministic v1 query template: {@code "<cuisines> recipe <dietaryFlags>"}. Prompt-engineering
   * the query is a future track (worth user review).
   */
  private String buildQueryString(DiscoveryConstraints constraints) {
    StringBuilder sb = new StringBuilder();
    if (constraints != null
        && constraints.requiredCuisines() != null
        && !constraints.requiredCuisines().isEmpty()) {
      sb.append(String.join(" ", constraints.requiredCuisines())).append(' ');
    }
    sb.append("recipe");
    if (constraints != null && constraints.dietaryFlags() != null) {
      for (String df : constraints.dietaryFlags()) {
        sb.append(' ').append(df);
      }
    }
    return sb.toString().trim();
  }
}
