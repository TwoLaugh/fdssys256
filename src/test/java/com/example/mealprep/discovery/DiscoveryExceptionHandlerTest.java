package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.DiscoveryExceptionHandler;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.exception.DiscoveryAllSourcesUnavailableException;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.exception.DiscoveryJobAlreadyTerminalException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.exception.DiscoveryJobTimeoutException;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Unit coverage of {@link DiscoveryExceptionHandler}: each handler must produce the right HTTP
 * status, application/problem+json content type, populated detail/type/title slug, and the
 * exception-specific {@code errors} / {@code failedSources} properties.
 */
class DiscoveryExceptionHandlerTest {

  private final DiscoveryExceptionHandler handler = new DiscoveryExceptionHandler();

  private HttpServletRequest req(String uri) {
    HttpServletRequest r = mock(HttpServletRequest.class);
    when(r.getRequestURI()).thenReturn(uri);
    return r;
  }

  @Test
  void handleJobNotFound_returns404_problemJson() {
    UUID jobId = UUID.randomUUID();
    ResponseEntity<ProblemDetail> resp =
        handler.handleJobNotFound(new DiscoveryJobNotFoundException(jobId), req("/x"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(resp.getBody().getTitle()).isEqualTo("Discovery job not found");
    assertThat(resp.getBody().getType().toString()).contains("discovery-job-not-found");
    assertThat(resp.getBody().getInstance().toString()).isEqualTo("/x");
  }

  @Test
  void handleSourceNotFound_returns404_problemJson() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleSourceNotFound(new DiscoverySourceNotFoundException("src_a"), req("/y"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getBody().getType().toString()).contains("discovery-source-not-found");
  }

  @Test
  void handleJobAlreadyTerminal_returns422_problemJson() {
    UUID jobId = UUID.randomUUID();
    ResponseEntity<ProblemDetail> resp =
        handler.handleJobAlreadyTerminal(
            new DiscoveryJobAlreadyTerminalException(jobId, DiscoveryJobStatus.SUCCEEDED),
            req("/z"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(resp.getBody().getType().toString()).contains("discovery-job-already-terminal");
  }

  @Test
  void handleConstraintInvalid_noErrors_doesNotAttachErrorsProperty() {
    // kills NegateConditionalsMutator on `!ex.errors().isEmpty()` — when errors empty no property.
    ResponseEntity<ProblemDetail> resp =
        handler.handleConstraintInvalid(
            new DiscoveryConstraintInvalidException("bad input"), req("/x"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    // getProperties() is null when no setProperty was ever called.
    java.util.Map<String, Object> props = resp.getBody().getProperties();
    assertThat(props == null || !props.containsKey("errors")).isTrue();
  }

  @Test
  void handleConstraintInvalid_withErrors_attachesErrorsList() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleConstraintInvalid(
            new DiscoveryConstraintInvalidException("bad", List.of("a", "b")), req("/x"));

    assertThat(resp.getBody().getProperties()).containsEntry("errors", List.of("a", "b"));
  }

  @Test
  void handleAllSourcesDown_returns502_problemJson() {
    UUID jobId = UUID.randomUUID();
    ResponseEntity<ProblemDetail> resp =
        handler.handleAllSourcesDown(
            new DiscoveryAllSourcesUnavailableException(jobId, List.of("a")), req("/x"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(resp.getBody().getProperties()).containsKey("failedSources");
    assertThat(resp.getBody().getType().toString()).contains("discovery-all-sources-unavailable");
  }

  @Test
  void handleAllSourcesDown_emptyFailedSources_doesNotAttachProperty() {
    UUID jobId = UUID.randomUUID();
    ResponseEntity<ProblemDetail> resp =
        handler.handleAllSourcesDown(
            new DiscoveryAllSourcesUnavailableException(jobId, List.of()), req("/x"));

    java.util.Map<String, Object> props = resp.getBody().getProperties();
    assertThat(props == null || !props.containsKey("failedSources")).isTrue();
  }

  @Test
  void handleTimeout_returns408_problemJson() {
    ResponseEntity<ProblemDetail> resp =
        handler.handleTimeout(
            new DiscoveryJobTimeoutException(UUID.randomUUID(), Duration.ofSeconds(60)), req("/x"));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
    assertThat(resp.getBody().getType().toString()).contains("discovery-job-timeout");
  }
}
