package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryHttpFetcher;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.config.HttpFetchException;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.source.ReferenceCuratedSource;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-unit coverage of {@link ReferenceCuratedSource}. The package-private test-only constructor
 * aims the sitemap fetch at a fake URL; the {@link DiscoveryHttpFetcher} is mocked. Replaces the IT
 * baseline that left every mutant NO_COVERAGE.
 */
@ExtendWith(MockitoExtension.class)
class ReferenceCuratedSourceTest {

  private static final Instant NOW = Instant.parse("2026-05-13T12:00:00Z");
  private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private DiscoveryHttpFetcher httpFetcher;

  private ReferenceCuratedSource source;
  private final DiscoveryProperties properties =
      new DiscoveryProperties(
          Duration.ofMinutes(10),
          30,
          Duration.ofSeconds(60),
          Duration.ofHours(1),
          Duration.ofHours(6),
          null);

  @BeforeEach
  void setUp() throws Exception {
    JsonLdRecipeExtractor extractor = new JsonLdRecipeExtractor(new ObjectMapper());
    source = construct(httpFetcher, extractor, properties, fixedClock, "https://test/sitemap.xml");
  }

  /** Reflection — the package-private test constructor lives in the same package. */
  private static ReferenceCuratedSource construct(
      DiscoveryHttpFetcher http,
      JsonLdRecipeExtractor extractor,
      DiscoveryProperties props,
      Clock clock,
      String sitemapUrl)
      throws Exception {
    Constructor<ReferenceCuratedSource> ctor =
        ReferenceCuratedSource.class.getDeclaredConstructor(
            DiscoveryHttpFetcher.class,
            JsonLdRecipeExtractor.class,
            DiscoveryProperties.class,
            Clock.class,
            String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(http, extractor, props, clock, sitemapUrl);
  }

  // ===================== identity =====================

  @Test
  void key_returnsBbcGoodFood() {
    // kills EmptyObjectReturnValsMutator at ReferenceCuratedSource.java:90.
    assertThat(source.key()).isEqualTo("bbc_good_food");
  }

  @Test
  void kind_returnsSitemap() {
    // kills NullReturnValsMutator at ReferenceCuratedSource.java:95.
    assertThat(source.kind()).isEqualTo(DiscoverySourceKind.SITEMAP);
  }

  @Test
  void robotsTxtUri_returnsBbcGoodFoodRobotsUrl() {
    // kills EmptyObjectReturnValsMutator at ReferenceCuratedSource.java:100 — the URI must point
    // to bbcgoodfood.com/robots.txt, NOT Optional.empty().
    assertThat(source.robotsTxtUri()).isPresent();
    assertThat(source.robotsTxtUri().get().toString()).contains("bbcgoodfood.com/robots.txt");
  }

  // ===================== search → sitemap parsing =====================

  @Test
  void search_sendsDefaultCrawlerUserAgent() {
    // kills EmptyObjectReturnValsMutator at crawlerUserAgent line 187 — must be the project UA
    // string, NOT an empty one. We assert by capturing the userAgent passed to httpFetcher.
    String sitemap =
        "<urlset><url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url></urlset>";
    org.mockito.ArgumentCaptor<String> uaCap = org.mockito.ArgumentCaptor.forClass(String.class);
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), uaCap.capture()))
        .thenReturn(sitemap);

    source.search(new DiscoveryQuery(sampleConstraints(), 5, "ignored-by-source"));

    assertThat(uaCap.getValue())
        .isEqualTo(
            com.example.mealprep.discovery.config.GoogleCustomSearchConfig.DEFAULT_USER_AGENT);
  }

  @Test
  void search_sitemapWithRecipeUrls_returnsCappedCandidates() {
    // kills NegateConditionalsMutator + ConditionalsBoundaryMutator at line 109 (`out.size() >=
    // cap`) AND EmptyObjectReturnValsMutator at line 115 (must return populated list, not empty).
    String sitemap =
        "<urlset><url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url>"
            + "<url><loc>https://www.bbcgoodfood.com/about</loc></url>"
            + "<url><loc>https://www.bbcgoodfood.com/recipes/r2</loc></url>"
            + "<url><loc>https://www.bbcgoodfood.com/recipes/r3</loc></url></urlset>";
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn(sitemap);

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 2, "UA");
    List<DiscoveryCandidate> result = source.search(q);

    // Only recipe-path URLs, capped at maxResults=2.
    assertThat(result).hasSize(2);
    assertThat(result.get(0).candidateUrl()).contains("/recipes/");
    assertThat(result.get(0).sourceKey()).isEqualTo("bbc_good_food");
  }

  @Test
  void search_zeroCap_returnsEmpty() {
    // kills ConditionalsBoundaryMutator at line 109 — cap of 0 → empty list (even with content).
    String sitemap =
        "<urlset><url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url></urlset>";
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn(sitemap);

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 0, "UA");
    assertThat(source.search(q)).isEmpty();
  }

  @Test
  void search_sitemapFetchFails_throwsDiscoverySourceUnavailable() {
    // Exercises parseSitemap's exception branch via HttpFetchException → wrap as
    // DiscoverySourceUnavailable.
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString()))
        .thenThrow(new HttpFetchException("HTTP 500", 500, null));

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");

    assertThatThrownBy(() -> source.search(q))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("sitemap fetch failed");
  }

  @Test
  void search_blankSitemap_throwsDiscoverySourceUnavailable() {
    // kills NegateConditionalsMutator at parseSitemap line 152 (`xml == null || xml.isBlank()`).
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn("   ");

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");

    assertThatThrownBy(() -> source.search(q))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("sitemap empty");
  }

  @Test
  void search_sitemapWithDoctypeDeclaration_isRejected() {
    // kills VoidMethodCallMutator at parseSitemap line 159 (disallow-doctype-decl). A sitemap with
    // a DOCTYPE must be rejected by the parser; without the hardening call, the parser would
    // accept it.
    String sitemap =
        "<!DOCTYPE urlset>"
            + "<urlset><url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url></urlset>";
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn(sitemap);

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");

    assertThatThrownBy(() -> source.search(q))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("sitemap parse failed");
  }

  @Test
  void search_malformedSitemapXml_throwsDiscoverySourceUnavailable() {
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn("<not-xml");

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");

    assertThatThrownBy(() -> source.search(q))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("sitemap parse failed");
  }

  @Test
  void search_locWithoutRecipesPath_filteredOut() {
    // kills NegateConditionalsMutator at parseSitemap line 170 (the path-filter check) plus the
    // line 168 null-guard. Non-recipe URLs must be skipped.
    String sitemap =
        "<urlset><url><loc>https://www.bbcgoodfood.com/about</loc></url>"
            + "<url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url></urlset>";
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn(sitemap);

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");

    assertThat(source.search(q))
        .hasSize(1)
        .first()
        .extracting(DiscoveryCandidate::candidateUrl)
        .isEqualTo("https://www.bbcgoodfood.com/recipes/r1");
  }

  // ===================== cache TTL =====================

  @Test
  void search_cachedSitemap_secondCallDoesNotRefetch_andReturnsRecipeUrls() {
    // kills ConditionalsBoundaryMutator at sitemapRecipeUrls line 136 (the `compareTo(ttl) <= 0`
    // check) AND EmptyObjectReturnValsMutator at line 137 — the cache-hit branch must return the
    // populated cached list, not Collections.emptyList.
    String sitemap =
        "<urlset><url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url></urlset>";
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn(sitemap);

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");
    List<DiscoveryCandidate> first = source.search(q);
    List<DiscoveryCandidate> second = source.search(q);

    assertThat(first).hasSize(1);
    assertThat(second).hasSize(1);
    assertThat(second.get(0).candidateUrl()).isEqualTo("https://www.bbcgoodfood.com/recipes/r1");
    verify(httpFetcher, times(1)).getString(eq("https://test/sitemap.xml"), anyString());
  }

  @Test
  void search_cacheNullFirstCall_fetches() {
    // kills NegateConditionalsMutator at sitemapRecipeUrls line 135 (`cached == null`). First call
    // must fetch (no cache).
    String sitemap =
        "<urlset><url><loc>https://www.bbcgoodfood.com/recipes/r1</loc></url></urlset>";
    when(httpFetcher.getString(eq("https://test/sitemap.xml"), anyString())).thenReturn(sitemap);

    DiscoveryQuery q = new DiscoveryQuery(sampleConstraints(), 5, "UA");
    source.search(q);

    verify(httpFetcher, times(1)).getString(eq("https://test/sitemap.xml"), anyString());
  }

  // ===================== fetchRecipe =====================

  @Test
  void fetchRecipe_jsonLdPage_returnsParsedRecipe() {
    // kills NullReturnValsMutator at fetchRecipe line 127 — must return a real recipe.
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"R\",\"recipeIngredient\":[\"a\"],"
            + "\"recipeInstructions\":[\"b\"]}"
            + "</script></head></html>";
    when(httpFetcher.getString(eq("https://www.bbcgoodfood.com/recipes/r1"), anyString()))
        .thenReturn(html);

    ParsedRecipe r =
        source.fetchRecipe(
            new DiscoveryCandidate(
                "bbc_good_food", "https://www.bbcgoodfood.com/recipes/r1", null, null, Map.of()));

    assertThat(r).isNotNull();
    assertThat(r.name()).isEqualTo("R");
  }

  @Test
  void fetchRecipe_httpError_throwsExtractionFailed() {
    when(httpFetcher.getString(anyString(), anyString()))
        .thenThrow(new HttpFetchException("HTTP 500", 500, null));

    assertThatThrownBy(
            () ->
                source.fetchRecipe(
                    new DiscoveryCandidate(
                        "bbc_good_food",
                        "https://www.bbcgoodfood.com/recipes/r1",
                        null,
                        null,
                        Map.of())))
        .isInstanceOf(ExtractionFailedException.class)
        .hasMessageContaining("fetch failed");
  }

  @Test
  void fetchRecipe_noJsonLd_throwsExtractionFailed() {
    // kills NullReturnValsMutator at lambda$fetchRecipe$0 line 129.
    when(httpFetcher.getString(anyString(), anyString()))
        .thenReturn("<html><body>nothing</body></html>");

    assertThatThrownBy(
            () ->
                source.fetchRecipe(
                    new DiscoveryCandidate(
                        "bbc_good_food",
                        "https://www.bbcgoodfood.com/recipes/r1",
                        null,
                        null,
                        Map.of())))
        .isInstanceOf(ExtractionFailedException.class)
        .hasMessageContaining("no_jsonld");
  }

  private DiscoveryConstraints sampleConstraints() {
    return new DiscoveryConstraints(
        1, List.of(), List.of(), null, List.of(), List.of(), List.of(), 3);
  }
}
