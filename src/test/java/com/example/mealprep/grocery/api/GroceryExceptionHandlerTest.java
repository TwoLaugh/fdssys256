package com.example.mealprep.grocery.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.LineFulfilmentStatus;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.exception.GroceryOrderNotFoundException;
import com.example.mealprep.grocery.exception.GrocerySubstitutionProposalNotFoundException;
import com.example.mealprep.grocery.exception.IllegalOrderTransitionException;
import com.example.mealprep.grocery.exception.IllegalSubstitutionStateException;
import com.example.mealprep.grocery.exception.LineAlreadyBoughtException;
import com.example.mealprep.grocery.exception.LineNotBoughtException;
import com.example.mealprep.grocery.exception.OrderConcurrencyConflictException;
import com.example.mealprep.grocery.exception.OrderHasOutstandingProposalsException;
import com.example.mealprep.grocery.exception.ProviderNotConfiguredException;
import com.example.mealprep.grocery.exception.ProviderUnavailableException;
import com.example.mealprep.grocery.exception.ShoppingListLineNotFoundException;
import com.example.mealprep.grocery.exception.ShoppingListNotFoundException;
import com.example.mealprep.grocery.exception.UnknownMappingKeyException;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for {@link GroceryExceptionHandler}. Verifies that every mapped exception produces a
 * {@link ProblemDetail} with the right HTTP status, type slug, title, content-type (application/
 * problem+json) and any extension properties (orderId, providerKey, attemptedDecision, etc.). The
 * handler is a pure mapper — no Spring container needed.
 */
@ExtendWith(MockitoExtension.class)
class GroceryExceptionHandlerTest {

  @Mock private HttpServletRequest req;

  private final GroceryExceptionHandler handler = new GroceryExceptionHandler();

  @BeforeEach
  void setUp() {
    when(req.getRequestURI()).thenReturn("/api/v1/grocery/orders/abc");
  }

  // ============================== 404 mappers ==============================

  @Test
  void shoppingListNotFound_returns404_withTypeSlug() {
    ShoppingListNotFoundException ex = new ShoppingListNotFoundException(UUID.randomUUID());
    ResponseEntity<ProblemDetail> r = handler.handleShoppingListNotFound(ex, req);
    assertProblem(r, HttpStatus.NOT_FOUND, "shopping-list-not-found", "Shopping list not found");
    assertThat(r.getBody().getDetail()).isEqualTo(ex.getMessage());
  }

  @Test
  void shoppingListLineNotFound_returns404_withTypeSlug() {
    ShoppingListLineNotFoundException ex = new ShoppingListLineNotFoundException(UUID.randomUUID());
    ResponseEntity<ProblemDetail> r = handler.handleShoppingListLineNotFound(ex, req);
    assertProblem(
        r, HttpStatus.NOT_FOUND, "shopping-list-line-not-found", "Shopping list line not found");
  }

  @Test
  void groceryOrderNotFound_returns404_withTypeSlug() {
    GroceryOrderNotFoundException ex = new GroceryOrderNotFoundException(UUID.randomUUID());
    ResponseEntity<ProblemDetail> r = handler.handleGroceryOrderNotFound(ex, req);
    assertProblem(r, HttpStatus.NOT_FOUND, "grocery-order-not-found", "Grocery order not found");
  }

  @Test
  void substitutionProposalNotFound_returns404_withTypeSlug() {
    GrocerySubstitutionProposalNotFoundException ex =
        new GrocerySubstitutionProposalNotFoundException(UUID.randomUUID());
    ResponseEntity<ProblemDetail> r = handler.handleSubstitutionProposalNotFound(ex, req);
    assertProblem(
        r,
        HttpStatus.NOT_FOUND,
        "grocery-substitution-proposal-not-found",
        "Grocery substitution proposal not found");
  }

  // ============================== 409 mappers (with extension props)
  // ==============================

  @Test
  void illegalOrderTransition_returns409_carriesFromAndTo() {
    IllegalOrderTransitionException ex =
        new IllegalOrderTransitionException(
            GroceryOrderStatus.RECONCILED, GroceryOrderStatus.CANCELLED);
    ResponseEntity<ProblemDetail> r = handler.handleIllegalOrderTransition(ex, req);
    assertProblem(r, HttpStatus.CONFLICT, "illegal-order-transition", "Illegal order transition");
    assertThat(r.getBody().getProperties()).containsEntry("from", GroceryOrderStatus.RECONCILED);
    assertThat(r.getBody().getProperties()).containsEntry("to", GroceryOrderStatus.CANCELLED);
  }

  @Test
  void illegalSubstitutionState_returns409_carriesProposalIdAndAttemptedDecision() {
    UUID id = UUID.randomUUID();
    IllegalSubstitutionStateException ex =
        new IllegalSubstitutionStateException(id, SubstitutionProposalStatus.ACCEPTED);
    ResponseEntity<ProblemDetail> r = handler.handleIllegalSubstitutionState(ex, req);
    assertProblem(
        r, HttpStatus.CONFLICT, "illegal-substitution-state", "Illegal substitution resolve");
    assertThat(r.getBody().getProperties()).containsEntry("proposalId", id);
    assertThat(r.getBody().getProperties())
        .containsEntry("attemptedDecision", SubstitutionProposalStatus.ACCEPTED);
  }

  @Test
  void orderConcurrencyConflict_returns409() {
    OrderConcurrencyConflictException ex = new OrderConcurrencyConflictException("locked");
    ResponseEntity<ProblemDetail> r = handler.handleOrderConcurrencyConflict(ex, req);
    assertProblem(
        r, HttpStatus.CONFLICT, "order-concurrency-conflict", "Order concurrency conflict");
  }

  @Test
  void lineAlreadyBought_returns409_carriesShoppingListLineId() {
    UUID lineId = UUID.randomUUID();
    LineAlreadyBoughtException ex = new LineAlreadyBoughtException(lineId);
    ResponseEntity<ProblemDetail> r = handler.handleLineAlreadyBought(ex, req);
    assertProblem(
        r, HttpStatus.CONFLICT, "line-already-bought", "Shopping list line already bought");
    assertThat(r.getBody().getProperties()).containsEntry("shoppingListLineId", lineId);
  }

  @Test
  void lineNotBought_returns409_carriesIdAndCurrentStatus() {
    UUID lineId = UUID.randomUUID();
    LineNotBoughtException ex = new LineNotBoughtException(lineId, LineFulfilmentStatus.UNFILLED);
    ResponseEntity<ProblemDetail> r = handler.handleLineNotBought(ex, req);
    assertProblem(r, HttpStatus.CONFLICT, "line-not-bought", "Shopping list line not bought");
    assertThat(r.getBody().getProperties()).containsEntry("shoppingListLineId", lineId);
    assertThat(r.getBody().getProperties())
        .containsEntry("currentStatus", LineFulfilmentStatus.UNFILLED);
  }

  @Test
  void duplicateGroceryImport_returns409() {
    DuplicateGroceryImportException ex =
        new DuplicateGroceryImportException(UUID.randomUUID(), ItemSource.TESCO_ORDER, "ref");
    ResponseEntity<ProblemDetail> r = handler.handleDuplicateGroceryImport(ex, req);
    assertProblem(
        r, HttpStatus.CONFLICT, "duplicate-grocery-import", "Grocery import already applied");
  }

  // ============================== 422 mappers ==============================

  @Test
  void orderHasOutstandingProposals_returns422_carriesOrderIdAndCount() {
    UUID orderId = UUID.randomUUID();
    OrderHasOutstandingProposalsException ex =
        new OrderHasOutstandingProposalsException(orderId, 3L);
    ResponseEntity<ProblemDetail> r = handler.handleOrderHasOutstandingProposals(ex, req);
    assertProblem(
        r,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "order-has-outstanding-proposals",
        "Order has outstanding substitution proposals");
    assertThat(r.getBody().getProperties()).containsEntry("orderId", orderId);
    assertThat(r.getBody().getProperties()).containsEntry("outstandingCount", 3L);
  }

  @Test
  void providerNotConfigured_returns422_carriesProviderKey() {
    ProviderNotConfiguredException ex = new ProviderNotConfiguredException("tesco");
    ResponseEntity<ProblemDetail> r = handler.handleProviderNotConfigured(ex, req);
    assertProblem(
        r, HttpStatus.UNPROCESSABLE_ENTITY, "provider-not-configured", "Provider not configured");
    assertThat(r.getBody().getProperties()).containsEntry("providerKey", "tesco");
  }

  // ============================== 503 mappers ==============================

  @Test
  void providerUnavailable_returns503_carriesProviderKeyAndReason() {
    ProviderUnavailableException ex =
        new ProviderUnavailableException("tesco", "login_required", "session expired");
    ResponseEntity<ProblemDetail> r = handler.handleProviderUnavailable(ex, req);
    assertProblem(
        r, HttpStatus.SERVICE_UNAVAILABLE, "provider-unavailable", "Provider unavailable");
    assertThat(r.getBody().getProperties()).containsEntry("providerKey", "tesco");
    assertThat(r.getBody().getProperties()).containsEntry("reason", "login_required");
  }

  @Test
  void aiUnavailable_returns503() {
    AiUnavailableException ex = new AiUnavailableException("cost cap reached");
    ResponseEntity<ProblemDetail> r = handler.handleAiUnavailable(ex, req);
    assertProblem(r, HttpStatus.SERVICE_UNAVAILABLE, "ai-unavailable", "AI unavailable");
    assertThat(r.getBody().getDetail()).isEqualTo("cost cap reached");
  }

  // ============================== 400 mappers ==============================

  @Test
  void unknownMappingKey_returns400_carriesKey() {
    UnknownMappingKeyException ex = new UnknownMappingKeyException("   ");
    ResponseEntity<ProblemDetail> r = handler.handleUnknownMappingKey(ex, req);
    assertProblem(
        r, HttpStatus.BAD_REQUEST, "unknown-mapping-key", "Unknown ingredient mapping key");
    assertThat(r.getBody().getProperties()).containsEntry("ingredientMappingKey", "   ");
  }

  // ============================== shared assertions ==============================

  /**
   * Verifies the standard ProblemDetail shape: status, content-type, type-URI suffix, title,
   * instance.
   */
  private void assertProblem(
      ResponseEntity<ProblemDetail> r, HttpStatus status, String typeSlug, String title) {
    assertThat(r.getStatusCode()).isEqualTo(status);
    assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = r.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(status.value());
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/" + typeSlug);
    assertThat(pd.getTitle()).isEqualTo(title);
    assertThat(pd.getInstance().toString()).isEqualTo("/api/v1/grocery/orders/abc");
  }
}
