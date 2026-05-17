package com.example.mealprep.discovery.source;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryHttpFetcher;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.config.HttpFetchException;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.NodeList;

/**
 * Reference curated {@code DiscoverySource} ({@code source_key = bbc_good_food}) demonstrating the
 * curated-site pattern: sitemap enumeration → {@link DiscoveryCandidate} list; per-candidate
 * full-page fetch via the shared {@link JsonLdRecipeExtractor}. Per 01e ticket §
 * ReferenceCuratedSource. Future tickets add more per-site impls; the registry + runner stay
 * source-agnostic.
 *
 * <p><b>No Spring Web imports</b> ({@code springWebStaysInApi}): HTTP via {@link
 * DiscoveryHttpFetcher}; sitemap XML via JDK JAXP (no new dependency). The sitemap is cached
 * per-instance for {@link DiscoveryProperties#sitemapCacheTtl} (default 6h) — long-running runner
 * instances refresh rather than caching once-per-jvm (worth user review).
 *
 * <p>Test-only constructor lets ITs aim the sitemap URL at a WireMock origin; the prod constructor
 * is {@code @Autowired} (multi-constructor bean gotcha — wave-3 retro).
 */
@Component
public class ReferenceCuratedSource implements DiscoverySource {

  private static final Logger log = LoggerFactory.getLogger(ReferenceCuratedSource.class);
  private static final String KEY = "bbc_good_food";
  private static final String DEFAULT_SITEMAP_URL = "https://www.bbcgoodfood.com/sitemap.xml";
  private static final String ROBOTS_URI = "https://www.bbcgoodfood.com/robots.txt";
  private static final String RECIPE_PATH_FILTER = "/recipes/";

  private final DiscoveryHttpFetcher httpFetcher;
  private final JsonLdRecipeExtractor jsonLdExtractor;
  private final DiscoveryProperties properties;
  private final Clock clock;
  private final String sitemapUrl;
  private final AtomicReference<CachedSitemap> cache = new AtomicReference<>();

  @Autowired
  public ReferenceCuratedSource(
      DiscoveryHttpFetcher httpFetcher,
      JsonLdRecipeExtractor jsonLdExtractor,
      DiscoveryProperties properties,
      Clock clock) {
    this(httpFetcher, jsonLdExtractor, properties, clock, DEFAULT_SITEMAP_URL);
  }

  /** Test-only — aims the sitemap fetch at a WireMock-served {@code http://} origin. */
  ReferenceCuratedSource(
      DiscoveryHttpFetcher httpFetcher,
      JsonLdRecipeExtractor jsonLdExtractor,
      DiscoveryProperties properties,
      Clock clock,
      String sitemapUrl) {
    this.httpFetcher = httpFetcher;
    this.jsonLdExtractor = jsonLdExtractor;
    this.properties = properties;
    this.clock = clock;
    this.sitemapUrl = sitemapUrl;
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public DiscoverySourceKind kind() {
    return DiscoverySourceKind.SITEMAP;
  }

  @Override
  public Optional<URI> robotsTxtUri() {
    return Optional.of(URI.create(ROBOTS_URI));
  }

  @Override
  public List<DiscoveryCandidate> search(DiscoveryQuery query) {
    List<String> recipeUrls = sitemapRecipeUrls();
    int cap = Math.max(0, query.maxResults());
    List<DiscoveryCandidate> out = new ArrayList<>();
    for (String url : recipeUrls) {
      if (out.size() >= cap) {
        break;
      }
      // BBC Good Food's sitemap carries no rich snippets; v1 leaves them null.
      out.add(new DiscoveryCandidate(KEY, url, null, null, Map.of()));
    }
    return out;
  }

  @Override
  public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
    String html;
    try {
      html = httpFetcher.getString(candidate.candidateUrl(), crawlerUserAgent());
    } catch (HttpFetchException e) {
      throw new ExtractionFailedException(
          candidate.candidateUrl(), "fetch failed: " + e.getMessage());
    }
    return jsonLdExtractor
        .extract(html, candidate.candidateUrl())
        .orElseThrow(() -> new ExtractionFailedException(candidate.candidateUrl(), "no_jsonld"));
  }

  private List<String> sitemapRecipeUrls() {
    Instant now = Instant.now(clock);
    CachedSitemap cached = cache.get();
    if (cached != null
        && Duration.between(cached.fetchedAt(), now).compareTo(properties.sitemapCacheTtl()) <= 0) {
      return cached.recipeUrls();
    }
    String xml;
    try {
      xml = httpFetcher.getString(sitemapUrl, crawlerUserAgent());
    } catch (HttpFetchException e) {
      throw new DiscoverySourceUnavailableException(
          KEY, "sitemap fetch failed: " + e.getMessage(), e);
    }
    List<String> recipeUrls = parseSitemap(xml);
    cache.set(new CachedSitemap(recipeUrls, now));
    return recipeUrls;
  }

  private List<String> parseSitemap(String xml) {
    if (xml == null || xml.isBlank()) {
      throw new DiscoverySourceUnavailableException(KEY, "sitemap empty", null);
    }
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      // Harden the parser (XXE) — sitemaps need no DTDs / external entities.
      dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      dbf.setExpandEntityReferences(false);
      DocumentBuilder builder = dbf.newDocumentBuilder();
      org.w3c.dom.Document doc =
          builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      NodeList locs = doc.getElementsByTagName("loc");
      List<String> recipeUrls = new ArrayList<>();
      for (int i = 0; i < locs.getLength(); i++) {
        String loc = locs.item(i).getTextContent();
        if (loc != null) {
          String trimmed = loc.trim();
          if (trimmed.contains(RECIPE_PATH_FILTER)) {
            recipeUrls.add(trimmed);
          }
        }
      }
      log.debug(
          "bbc_good_food sitemap parsed: {} loc(s), {} recipe url(s)",
          locs.getLength(),
          recipeUrls.size());
      return recipeUrls;
    } catch (Exception e) {
      throw new DiscoverySourceUnavailableException(
          KEY, "sitemap parse failed: " + e.getMessage(), e);
    }
  }

  private String crawlerUserAgent() {
    return com.example.mealprep.discovery.config.GoogleCustomSearchConfig.DEFAULT_USER_AGENT;
  }

  private record CachedSitemap(List<String> recipeUrls, Instant fetchedAt) {}
}
