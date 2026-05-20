package com.example.mealprep.discovery.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Pure-unit coverage of {@link DiscoveryHttpFetcher} — string + JSON paths and error mapping. */
class DiscoveryHttpFetcherTest {

  private WireMockServer wm;
  private DiscoveryHttpFetcher fetcher;

  @BeforeEach
  void setUp() {
    wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
    WireMock.configureFor("localhost", wm.port());
    RestClient client = RestClient.builder().build();
    fetcher = new DiscoveryHttpFetcher(client);
  }

  @AfterEach
  void tearDown() {
    wm.stop();
  }

  @Test
  void getString_2xx_returnsBody() {
    stubFor(get(urlPathEqualTo("/x")).willReturn(aResponse().withStatus(200).withBody("hello")));

    assertThat(fetcher.getString("http://localhost:" + wm.port() + "/x", "UA")).isEqualTo("hello");
  }

  @Test
  void getString_404_throwsHttpFetchException_withStatusCode() {
    stubFor(get(urlPathEqualTo("/x")).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> fetcher.getString("http://localhost:" + wm.port() + "/x", "UA"))
        .isInstanceOf(HttpFetchException.class)
        .extracting("statusCode")
        .isEqualTo(404);
  }

  @Test
  void getString_500_throwsHttpFetchException_withStatusCode() {
    stubFor(get(urlPathEqualTo("/x")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> fetcher.getString("http://localhost:" + wm.port() + "/x", "UA"))
        .isInstanceOf(HttpFetchException.class)
        .extracting("statusCode")
        .isEqualTo(500);
  }

  @Test
  void getString_networkError_throwsHttpFetchExceptionWithNullStatus() {
    // Hitting an unreachable port → RestClientException → "network error" with null status.
    assertThatThrownBy(() -> fetcher.getString("http://localhost:1/x", "UA"))
        .isInstanceOf(HttpFetchException.class)
        .extracting("statusCode")
        .isNull();
  }

  @Test
  void getJson_2xx_parsesJson_andAddsQueryParamsToRequestUrl() {
    // kills VoidMethodCallMutator at DiscoveryHttpFetcher.java:67 — without the forEach the URL
    // would have no query params. We assert via WireMock's queryParam matcher.
    stubFor(
        get(urlMatching("/api/q\\?p1=v1.*"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"key\":\"value\"}")));

    Map<String, String> params = new LinkedHashMap<>();
    params.put("p1", "v1");
    JsonNode node = fetcher.getJson("http", "localhost:" + wm.port(), "/api/q", params, "UA");

    assertThat(node).isNotNull();
    assertThat(node.get("key").asText()).isEqualTo("value");
  }

  @Test
  void getJson_4xx_throwsHttpFetchException_withStatusCode() {
    stubFor(get(urlMatching("/api/q.*")).willReturn(aResponse().withStatus(429)));

    Map<String, String> params = new LinkedHashMap<>();
    params.put("p", "v");
    assertThatThrownBy(
            () -> fetcher.getJson("http", "localhost:" + wm.port(), "/api/q", params, "UA"))
        .isInstanceOf(HttpFetchException.class)
        .extracting("statusCode")
        .isEqualTo(429);
  }

  @Test
  void getJson_networkError_throwsHttpFetchExceptionWithNullStatus() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("p", "v");

    assertThatThrownBy(() -> fetcher.getJson("http", "localhost:1", "/api/q", params, "UA"))
        .isInstanceOf(HttpFetchException.class)
        .extracting("statusCode")
        .isNull();
  }
}
