package com.example.mealprep.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.config.DiscoveryHttpFetcher;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.source.ReferenceCuratedSource;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock IT for the reference curated source: sitemap fetch + {@code /recipes/} path filter,
 * sitemap fetch failure, parse failure, 6h cache TTL, and JSON-LD fetchRecipe. Uses the
 * package-private test constructor (reflection) to aim the sitemap fetch at WireMock.
 */
class ReferenceCuratedSourceIT {

  private WireMockServer wireMock;
  private DiscoveryHttpFetcher fetcher;
  private JsonLdRecipeExtractor extractor;

  @BeforeEach
  void start() {
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(10_000);
    f.setReadTimeout(10_000);
    fetcher = new DiscoveryHttpFetcher(RestClient.builder().requestFactory(f).build());
    extractor = new JsonLdRecipeExtractor(new ObjectMapper());
  }

  @AfterEach
  void stop() {
    wireMock.stop();
  }

  private String fixture(String name) throws Exception {
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("discovery/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private ReferenceCuratedSource source(DiscoveryProperties props, Clock clock) throws Exception {
    String sitemapUrl = "http://localhost:" + wireMock.port() + "/sitemap.xml";
    Constructor<ReferenceCuratedSource> ctor =
        ReferenceCuratedSource.class.getDeclaredConstructor(
            DiscoveryHttpFetcher.class,
            JsonLdRecipeExtractor.class,
            DiscoveryProperties.class,
            Clock.class,
            String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(fetcher, extractor, props, clock, sitemapUrl);
  }

  private DiscoveryProperties props(Duration sitemapTtl) {
    return new DiscoveryProperties(
        Duration.ofMinutes(10), 30, Duration.ofSeconds(60), Duration.ofHours(1), sitemapTtl, null);
  }

  private DiscoveryQuery query(int max) {
    DiscoveryConstraints c =
        new DiscoveryConstraints(1, List.of(), List.of(), null, List.of(), List.of(), List.of(), 3);
    return new DiscoveryQuery(c, max, "MealPrepAI/1.0");
  }

  @Test
  void search_filtersRecipePathsAndCaps() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/sitemap.xml"))
            .willReturn(aResponse().withStatus(200).withBody(fixture("bbc-sitemap.xml"))));

    ReferenceCuratedSource src =
        source(props(Duration.ofHours(6)), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    List<DiscoveryCandidate> result = src.search(query(3));

    assertThat(result).hasSize(3);
    assertThat(result).allSatisfy(c -> assertThat(c.candidateUrl()).contains("/recipes/"));
    assertThat(result).allSatisfy(c -> assertThat(c.sourceKey()).isEqualTo("bbc_good_food"));
  }

  @Test
  void search_returnsAllRecipeUrlsWhenCapHigh() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/sitemap.xml"))
            .willReturn(aResponse().withStatus(200).withBody(fixture("bbc-sitemap.xml"))));

    ReferenceCuratedSource src =
        source(props(Duration.ofHours(6)), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    // fixture has 7 /recipes/ URLs out of 10 locs
    assertThat(src.search(query(50))).hasSize(7);
  }

  @Test
  void search_sitemapFetchFailure_throwsUnavailable() throws Exception {
    wireMock.stubFor(get(urlPathEqualTo("/sitemap.xml")).willReturn(aResponse().withStatus(503)));

    ReferenceCuratedSource src =
        source(props(Duration.ofHours(6)), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    assertThatThrownBy(() -> src.search(query(10)))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("sitemap fetch failed");
  }

  @Test
  void search_invalidXml_throwsUnavailable() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/sitemap.xml"))
            .willReturn(aResponse().withStatus(200).withBody("<urlset><loc>unterminated")));

    ReferenceCuratedSource src =
        source(props(Duration.ofHours(6)), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    assertThatThrownBy(() -> src.search(query(10)))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("sitemap parse failed");
  }

  @Test
  void search_twoCallsWithinTtl_secondHitsCache() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/sitemap.xml"))
            .willReturn(aResponse().withStatus(200).withBody(fixture("bbc-sitemap.xml"))));

    ReferenceCuratedSource src =
        source(props(Duration.ofHours(6)), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    src.search(query(5));
    src.search(query(5));

    wireMock.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/sitemap.xml")));
  }

  @Test
  void fetchRecipe_jsonLdPage_returnsParsed() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/recipes/x"))
            .willReturn(
                aResponse().withStatus(200).withBody(fixture("bbc-good-food-recipe.html"))));

    ReferenceCuratedSource src =
        source(props(Duration.ofHours(6)), Clock.fixed(Instant.now(), ZoneOffset.UTC));
    var parsed =
        src.fetchRecipe(
            new DiscoveryCandidate(
                "bbc_good_food",
                "http://localhost:" + wireMock.port() + "/recipes/x",
                null,
                null,
                Map.of()));

    assertThat(parsed.name()).isEqualTo("Classic Spaghetti Bolognese");
    assertThat(parsed.extractionMethod()).isEqualTo("json_ld");
  }
}
