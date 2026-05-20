package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.config.DiscoveryHttpFetcher;
import com.example.mealprep.discovery.config.GoogleCustomSearchConfig;
import com.example.mealprep.discovery.config.HttpFetchException;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.source.GoogleCustomSearchAdapter;
import com.example.mealprep.discovery.source.internal.GoogleCseDailyQuotaTracker;
import com.example.mealprep.discovery.source.internal.JsonLdRecipeExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the Google CSE error matrix (200 / empty items / 429 / 503 / timeout / quota-exhausted)
 * and {@code fetchRecipe} JSON-LD delegation, with a mocked {@link DiscoveryHttpFetcher} (no
 * Spring-Web types reach this adapter — that's the point of the fetcher seam).
 */
@ExtendWith(MockitoExtension.class)
class GoogleCustomSearchAdapterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock private DiscoveryHttpFetcher httpFetcher;
  @Mock private GoogleCseDailyQuotaTracker quotaTracker;

  private GoogleCustomSearchAdapter adapter;

  @BeforeEach
  void setUp() {
    GoogleCustomSearchConfig config =
        new GoogleCustomSearchConfig("test-key", "test-cx", 10, 50, null);
    adapter =
        new GoogleCustomSearchAdapter(
            config, quotaTracker, new JsonLdRecipeExtractor(MAPPER), httpFetcher);
  }

  private DiscoveryQuery query() {
    DiscoveryConstraints c =
        new DiscoveryConstraints(
            1,
            List.of("Thai"),
            List.of("dinner"),
            45,
            List.of(),
            List.of("vegetarian"),
            List.of(),
            3);
    return new DiscoveryQuery(c, 10, "MealPrepAI/1.0");
  }

  @Test
  void key_and_kind_matchSeed() {
    assertThat(adapter.key()).isEqualTo("google_cse");
    assertThat(adapter.kind().name()).isEqualTo("SEARCH_API");
    assertThat(adapter.robotsTxtUri()).isEmpty();
  }

  @Test
  void search_tenItems_returnsCandidatesWithSerpMetadata() throws Exception {
    String json =
        "{\"items\":[{\"title\":\"A\",\"link\":\"https://x.test/a\",\"displayLink\":\"x.test\","
            + "\"snippet\":\"sa\"},{\"title\":\"B\",\"link\":\"https://x.test/b\","
            + "\"displayLink\":\"x.test\",\"snippet\":\"sb\"}]}";
    when(quotaTracker.todaysCount()).thenReturn(0);
    when(httpFetcher.getJson(
            eq("https"), eq("www.googleapis.com"), eq("/customsearch/v1"), any(), anyString()))
        .thenReturn(MAPPER.readTree(json));

    List<DiscoveryCandidate> result = adapter.search(query());

    assertThat(result).hasSize(2);
    assertThat(result.get(0).sourceKey()).isEqualTo("google_cse");
    assertThat(result.get(0).candidateUrl()).isEqualTo("https://x.test/a");
    assertThat(result.get(0).snippetTitle()).isEqualTo("A");
    assertThat(result.get(0).sourceMetadata()).containsEntry("serpRank", "0");
    assertThat(result.get(1).sourceMetadata()).containsEntry("serpRank", "1");
    assertThat(result.get(0).sourceMetadata()).containsEntry("displayLink", "x.test");
    verify(quotaTracker, times(1)).recordCall();
  }

  @Test
  void search_emptyItems_returnsEmptyListNotException() throws Exception {
    when(quotaTracker.todaysCount()).thenReturn(0);
    when(httpFetcher.getJson(any(), any(), any(), any(), any()))
        .thenReturn(MAPPER.readTree("{\"items\":[]}"));

    assertThat(adapter.search(query())).isEmpty();
    verify(quotaTracker, times(1)).recordCall();
  }

  @Test
  void search_http429_throwsUnavailableAndCounts() {
    when(quotaTracker.todaysCount()).thenReturn(0);
    when(httpFetcher.getJson(any(), any(), any(), any(), any()))
        .thenThrow(new HttpFetchException("HTTP 429", 429, null));

    assertThatThrownBy(() -> adapter.search(query()))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("HTTP 429");
    verify(quotaTracker, times(1)).recordCall();
  }

  @Test
  void search_http503_throwsUnavailableAndCounts() {
    when(quotaTracker.todaysCount()).thenReturn(0);
    when(httpFetcher.getJson(any(), any(), any(), any(), any()))
        .thenThrow(new HttpFetchException("HTTP 503", 503, null));

    assertThatThrownBy(() -> adapter.search(query()))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("HTTP 503");
    verify(quotaTracker, times(1)).recordCall();
  }

  @Test
  void search_networkTimeout_throwsUnavailableNoCount() {
    when(quotaTracker.todaysCount()).thenReturn(0);
    when(httpFetcher.getJson(any(), any(), any(), any(), any()))
        .thenThrow(new HttpFetchException("network error", null, null));

    assertThatThrownBy(() -> adapter.search(query()))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("network error");
    verify(quotaTracker, never()).recordCall();
  }

  @Test
  void search_quotaExhausted_throwsWithoutHttpCall() {
    when(quotaTracker.todaysCount()).thenReturn(50);

    assertThatThrownBy(() -> adapter.search(query()))
        .isInstanceOf(DiscoverySourceUnavailableException.class)
        .hasMessageContaining("daily quota exhausted");
    verify(httpFetcher, never()).getJson(any(), any(), any(), any(), any());
    verify(quotaTracker, never()).recordCall();
  }

  @Test
  void fetchRecipe_jsonLdPage_returnsParsedRecipe() {
    String html =
        "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Recipe\",\"name\":\"Pad Thai\",\"recipeIngredient\":[\"noodles\"],"
            + "\"recipeInstructions\":[\"Cook.\"]}</script></head><body></body></html>";
    when(httpFetcher.getString(eq("https://x.test/a"), anyString())).thenReturn(html);

    ParsedRecipe r =
        adapter.fetchRecipe(
            new DiscoveryCandidate("google_cse", "https://x.test/a", null, null, Map.of()));

    assertThat(r.name()).isEqualTo("Pad Thai");
    assertThat(r.extractionMethod()).isEqualTo("json_ld");
  }

  @Test
  void fetchRecipe_noJsonLd_throwsExtractionFailed() {
    when(httpFetcher.getString(anyString(), anyString()))
        .thenReturn("<html><body>no recipe</body></html>");

    assertThatThrownBy(
            () ->
                adapter.fetchRecipe(
                    new DiscoveryCandidate("google_cse", "https://x.test/a", null, null, Map.of())))
        .isInstanceOf(ExtractionFailedException.class)
        .hasMessageContaining("no_jsonld");
  }

  @Test
  void fetchRecipe_http404_throwsExtractionFailed() {
    when(httpFetcher.getString(anyString(), anyString()))
        .thenThrow(new HttpFetchException("HTTP 404", 404, null));

    assertThatThrownBy(
            () ->
                adapter.fetchRecipe(
                    new DiscoveryCandidate("google_cse", "https://x.test/a", null, null, Map.of())))
        .isInstanceOf(ExtractionFailedException.class)
        .hasMessageContaining("fetch failed");
  }

  // ===================== buildQueryString branch kills =====================

  @Test
  void search_constraintsWithCuisinesAndDietaryFlags_buildsCorrectQueryString() throws Exception {
    // kills NegateConditionalsMutator survivors at GoogleCustomSearchAdapter.java:157, 158, 159
    // (the null + non-null + non-empty checks on requiredCuisines) AND lines 163 + 168
    // (dietaryFlags
    // loop + final trim). We capture the params and assert the exact `q` parameter.
    when(quotaTracker.todaysCount()).thenReturn(0);
    org.mockito.ArgumentCaptor<java.util.Map<String, String>> paramsCap =
        org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    when(httpFetcher.getJson(any(), any(), any(), paramsCap.capture(), any()))
        .thenReturn(MAPPER.readTree("{\"items\":[]}"));

    adapter.search(query());

    String q = paramsCap.getValue().get("q");
    // "Thai recipe vegetarian" — cuisines, then "recipe", then dietary flags. The query() helper
    // sets cuisines=["Thai"], dietaryFlags=["vegetarian"].
    assertThat(q).isEqualTo("Thai recipe vegetarian");
  }

  @Test
  void search_constraintsWithNullCuisinesAndDietaryFlags_queryIsJustRecipe() throws Exception {
    // kills NegateConditionalsMutator at lines 157 and 163 (null guards). With null lists the
    // query is simply "recipe".
    when(quotaTracker.todaysCount()).thenReturn(0);
    org.mockito.ArgumentCaptor<java.util.Map<String, String>> paramsCap =
        org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    when(httpFetcher.getJson(any(), any(), any(), paramsCap.capture(), any()))
        .thenReturn(MAPPER.readTree("{\"items\":[]}"));

    DiscoveryConstraints c = new DiscoveryConstraints(1, null, null, null, null, null, null, 3);
    adapter.search(new DiscoveryQuery(c, 5, "UA"));

    assertThat(paramsCap.getValue().get("q")).isEqualTo("recipe");
  }

  @Test
  void search_constraintsWithEmptyCuisinesList_queryIsJustRecipe() throws Exception {
    // kills NegateConditionalsMutator at line 159 (`!requiredCuisines().isEmpty()`). Empty list
    // skips the cuisines prefix.
    when(quotaTracker.todaysCount()).thenReturn(0);
    org.mockito.ArgumentCaptor<java.util.Map<String, String>> paramsCap =
        org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    when(httpFetcher.getJson(any(), any(), any(), paramsCap.capture(), any()))
        .thenReturn(MAPPER.readTree("{\"items\":[]}"));

    DiscoveryConstraints c =
        new DiscoveryConstraints(1, List.of(), null, null, null, null, null, 3);
    adapter.search(new DiscoveryQuery(c, 5, "UA"));

    assertThat(paramsCap.getValue().get("q")).isEqualTo("recipe");
  }

  @Test
  void search_constraintsWithNullConstraints_queryIsJustRecipe() throws Exception {
    // kills NegateConditionalsMutator at lines 157 + 163 (the outer null guards). A null
    // DiscoveryConstraints object yields "recipe".
    when(quotaTracker.todaysCount()).thenReturn(0);
    org.mockito.ArgumentCaptor<java.util.Map<String, String>> paramsCap =
        org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    when(httpFetcher.getJson(any(), any(), any(), paramsCap.capture(), any()))
        .thenReturn(MAPPER.readTree("{\"items\":[]}"));

    adapter.search(new DiscoveryQuery(null, 5, "UA"));

    assertThat(paramsCap.getValue().get("q")).isEqualTo("recipe");
  }

  // ===================== parseCandidates: cap on items =====================

  @Test
  void search_responseHasMoreItemsThanNumCap_resultIsCapped() throws Exception {
    // kills ConditionalsBoundaryMutator at parseCandidates line 133 (`out.size() >= num`). With
    // a num cap smaller than the items count the result is truncated.
    when(quotaTracker.todaysCount()).thenReturn(0);
    String json =
        "{\"items\":[{\"link\":\"https://x/1\",\"title\":\"1\"},{\"link\":\"https://x/2\",\"title\":\"2\"},"
            + "{\"link\":\"https://x/3\",\"title\":\"3\"}]}";
    when(httpFetcher.getJson(any(), any(), any(), any(), any())).thenReturn(MAPPER.readTree(json));

    // Use a small maxResults to drive num=min(resultsPerQuery=10, max(1, 1))=1.
    DiscoveryConstraints c = new DiscoveryConstraints(1, null, null, null, null, null, null, 1);
    List<DiscoveryCandidate> result = adapter.search(new DiscoveryQuery(c, 1, "UA"));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).candidateUrl()).isEqualTo("https://x/1");
  }

  @Test
  void search_responseMissingItemsKey_returnsEmptyList() throws Exception {
    // kills NegateConditionalsMutator at parseCandidates line 127 (`!response.has("items")`).
    when(quotaTracker.todaysCount()).thenReturn(0);
    when(httpFetcher.getJson(any(), any(), any(), any(), any()))
        .thenReturn(MAPPER.readTree("{\"otherKey\":1}"));

    assertThat(adapter.search(query())).isEmpty();
  }
}
