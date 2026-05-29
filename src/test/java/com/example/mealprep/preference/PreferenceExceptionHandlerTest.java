package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.PreferenceExceptionHandler;
import com.example.mealprep.preference.api.dto.RemovedTier1Constraint;
import com.example.mealprep.preference.api.dto.Tier1Category;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import com.example.mealprep.preference.exception.Tier1RemovalRequiresConfirmationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

  @Test
  void
      tier1RemovalRequiresConfirmation_maps_to_409_problemDetail_withReasonAndRemovedConstraints() {
    when(request.getRequestURI()).thenReturn(URI);
    List<RemovedTier1Constraint> removed =
        List.of(new RemovedTier1Constraint(Tier1Category.ALLERGY, "peanuts"));

    ResponseEntity<ProblemDetail> resp =
        handler.handleTier1RemovalRequiresConfirmation(
            new Tier1RemovalRequiresConfirmationException(removed), request);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(409);
    assertThat(pd.getTitle()).isEqualTo("Tier-1 hard-constraint removal requires confirmation");
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/tier1-removal-requires-confirmation");
    assertThat(pd.getInstance().toString()).isEqualTo(URI);
    assertThat(pd.getProperties()).isNotNull();
    assertThat(pd.getProperties().get("reason")).isEqualTo("TIER1_REMOVAL_REQUIRES_CONFIRMATION");
    assertThat(pd.getProperties().get("removedConstraints")).isEqualTo(removed);
  }

  @Test
  void tier1RemovalException_message_namesEveryRemovedConstraint_commaSeparated() {
    // Two removed constraints exercise the message builder's loop + the i>0 comma separator and the
    // size prefix — pins the human-readable detail so a future refactor can't silently drop items.
    Tier1RemovalRequiresConfirmationException ex =
        new Tier1RemovalRequiresConfirmationException(
            List.of(
                new RemovedTier1Constraint(Tier1Category.ALLERGY, "peanuts"),
                new RemovedTier1Constraint(Tier1Category.MEDICAL_DIET, "low_sodium")));

    String msg = ex.getMessage();
    assertThat(msg).isNotEmpty();
    assertThat(msg).contains("Removing 2 ");
    assertThat(msg).contains("confirmTier1Removals=true");
    // Both items present, joined by ", " in order (ALLERGY:peanuts, MEDICAL_DIET:low_sodium).
    assertThat(msg).contains("ALLERGY:peanuts, MEDICAL_DIET:low_sodium");
  }

  @Test
  void tier1RemovalException_message_singleConstraint_hasNoLeadingSeparator() {
    Tier1RemovalRequiresConfirmationException ex =
        new Tier1RemovalRequiresConfirmationException(
            List.of(new RemovedTier1Constraint(Tier1Category.SEVERE_INTOLERANCE, "gluten")));

    String msg = ex.getMessage();
    assertThat(msg).contains("Removing 1 ");
    assertThat(msg).endsWith("SEVERE_INTOLERANCE:gluten");
    // No spurious leading comma before the single item.
    assertThat(msg).doesNotContain(": , ");
  }
}
