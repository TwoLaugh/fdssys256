package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.PreferenceExceptionHandler;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for {@link PreferenceExceptionHandler}. Invokes the single handler directly and pins
 * the 404 status, the {@code application/problem+json} content type, and every ProblemDetail field
 * (detail / title / type-slug / instance) — killing the previously-uncovered status-constant and
 * string-literal mutants. Pure — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class PreferenceExceptionHandlerTest {

  private static final String URI = "/api/v1/preference/hard-constraints";

  @Mock private HttpServletRequest request;
  private final PreferenceExceptionHandler handler = new PreferenceExceptionHandler();

  @Test
  void hardConstraintsNotFound_maps_to_404_problemDetail() {
    when(request.getRequestURI()).thenReturn(URI);
    UUID userId = UUID.randomUUID();

    ResponseEntity<ProblemDetail> resp =
        handler.handleHardConstraintsNotFound(
            new HardConstraintsNotFoundException(userId), request);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(404);
    assertThat(pd.getTitle()).isEqualTo("Hard constraints not found");
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/hard-constraints-not-found");
    assertThat(pd.getInstance().toString()).isEqualTo(URI);
    assertThat(pd.getDetail()).contains(userId.toString());
  }
}
