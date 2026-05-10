package com.example.mealprep.recipe.config;

import com.example.mealprep.recipe.exception.RecipeImportFailureException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Fetches an HTTP URL for the recipe URL-import flow. Wraps any failure (timeout / 4xx / 5xx /
 * oversize body) into a {@link RecipeImportFailureException} with a {@code failureReason} string
 * the exception handler surfaces on the ProblemDetail extension.
 *
 * <p>Lives under {@code recipe.config} so the {@code ModuleBoundaryTest} {@code
 * springWebStaysInApi} rule (which permits Spring Web types under {@code api} or {@code config}) is
 * satisfied.
 *
 * <p>The class is a {@code @Component} (not {@code final}) so integration tests can inject a
 * {@code @MockBean} stub instead of hitting the real internet — mandatory for CI stability.
 */
@Component
public class UrlFetcher {

  private final RestClient restClient;
  private final RecipeImportConfig config;

  public UrlFetcher(RestClient recipeImportRestClient, RecipeImportConfig config) {
    this.restClient = recipeImportRestClient;
    this.config = config;
  }

  /**
   * GET the URL and return the body as a UTF-8 string. Throws {@link RecipeImportFailureException}
   * on any failure with a structured {@code failureReason}.
   */
  public String fetch(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException ex) {
      throw new RecipeImportFailureException("schema_mismatch", ex);
    }
    try {
      ResponseEntity<byte[]> response =
          restClient
              .get()
              .uri(uri)
              .header(HttpHeaders.USER_AGENT, config.getUserAgent())
              .retrieve()
              .toEntity(byte[].class);
      byte[] body = response.getBody();
      if (body == null) {
        throw new RecipeImportFailureException("schema_mismatch");
      }
      if (body.length > config.getMaxBytes()) {
        throw new RecipeImportFailureException("oversize");
      }
      return new String(body, StandardCharsets.UTF_8);
    } catch (HttpClientErrorException ex) {
      throw new RecipeImportFailureException("fetch_4xx_" + ex.getStatusCode().value(), ex);
    } catch (HttpServerErrorException ex) {
      throw new RecipeImportFailureException("fetch_5xx_" + ex.getStatusCode().value(), ex);
    } catch (ResourceAccessException ex) {
      if (ex.getCause() instanceof SocketTimeoutException) {
        throw new RecipeImportFailureException("fetch_timeout", ex);
      }
      throw new RecipeImportFailureException("fetch_io_error", ex);
    }
  }
}
