package com.example.mealprep.discovery.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin HTTP collaborator for the {@code discovery.source..} adapters.
 *
 * <p><b>Why it lives in {@code discovery.config}:</b> the project-wide {@code springWebStaysInApi}
 * ArchUnit rule forbids {@code org.springframework.web..} / {@code org.springframework.http..}
 * imports outside {@code ..api..} / {@code ..config..}. The {@code DiscoverySource} impls must live
 * in {@code discovery.source..} (the LLD "hard pocket", enforced by {@code DiscoveryBoundaryTest}).
 * Those two rules collide if a source uses {@code RestClient} directly. Resolution: this bean owns
 * all Spring Web types and exposes only JDK/Jackson-typed methods + a Spring-free {@link
 * HttpFetchException}. The source impls inject this and never see a Spring Web class.
 *
 * <p>Uses the shared {@code discoveryRestClient} (10s timeouts) from {@link DiscoveryHttpConfig}.
 */
@Component
public class DiscoveryHttpFetcher {

  private final RestClient discoveryRestClient;

  public DiscoveryHttpFetcher(RestClient discoveryRestClient) {
    this.discoveryRestClient = discoveryRestClient;
  }

  /**
   * GET an absolute URL and return the body as a UTF-8 string.
   *
   * @throws HttpFetchException on any non-2xx status, network error, or timeout. {@link
   *     HttpFetchException#statusCode()} carries the HTTP status when one was received (else {@code
   *     null}).
   */
  public String getString(String url, String userAgent) {
    try {
      return discoveryRestClient
          .get()
          .uri(url)
          .header(HttpHeaders.USER_AGENT, userAgent)
          .retrieve()
          .body(String.class);
    } catch (HttpClientErrorException | HttpServerErrorException e) {
      throw new HttpFetchException(
          "HTTP " + e.getStatusCode().value(), e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new HttpFetchException("network error", null, e);
    }
  }

  /**
   * GET {@code https://host/path} with the given query params and parse the body as a Jackson tree.
   *
   * @throws HttpFetchException as {@link #getString}.
   */
  public JsonNode getJson(
      String scheme, String host, String path, Map<String, String> queryParams, String userAgent) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.newInstance().scheme(scheme).host(host).path(path);
    queryParams.forEach(builder::queryParam);
    String uri = builder.build().toUriString();
    try {
      return discoveryRestClient
          .get()
          .uri(uri)
          .header(HttpHeaders.USER_AGENT, userAgent)
          .retrieve()
          .body(JsonNode.class);
    } catch (HttpClientErrorException | HttpServerErrorException e) {
      throw new HttpFetchException(
          "HTTP " + e.getStatusCode().value(), e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new HttpFetchException("network error", null, e);
    }
  }
}
