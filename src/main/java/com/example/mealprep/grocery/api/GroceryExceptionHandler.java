package com.example.mealprep.grocery.api;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.grocery.exception.GroceryOrderNotFoundException;
import com.example.mealprep.grocery.exception.GrocerySubstitutionProposalNotFoundException;
import com.example.mealprep.grocery.exception.IllegalOrderTransitionException;
import com.example.mealprep.grocery.exception.LineAlreadyBoughtException;
import com.example.mealprep.grocery.exception.LineNotBoughtException;
import com.example.mealprep.grocery.exception.OrderConcurrencyConflictException;
import com.example.mealprep.grocery.exception.OrderHasOutstandingProposalsException;
import com.example.mealprep.grocery.exception.ProviderNotConfiguredException;
import com.example.mealprep.grocery.exception.ProviderUnavailableException;
import com.example.mealprep.grocery.exception.ShoppingListLineNotFoundException;
import com.example.mealprep.grocery.exception.ShoppingListNotFoundException;
import com.example.mealprep.grocery.exception.UnknownMappingKeyException;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Grocery-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all. Per lld/grocery.md §Error responses (lines
 * 742-766) and ticket-01a §Exceptions. Never modifies {@code config/GlobalExceptionHandler.java}.
 *
 * <p>{@code ProviderPartialFailureException} is DELIBERATELY NOT mapped — it is a 200-in-body
 * "fail-forward" signal caught service-side, not an error (LLD lines 755 / 764).
 *
 * <p>{@code OptimisticLockException} (409) and {@code MethodArgumentNotValidException} (400) from
 * the LLD error table are cross-cutting and already mapped centrally by {@code
 * GlobalExceptionHandler} ({@code OptimisticLockingFailureException} → 409, {@code
 * MethodArgumentNotValidException} → 400 with {@code errors[]}); they are not duplicated here.
 *
 * <p>{@code AiUnavailableException} is the ai module's published graceful-degrade signal; the
 * grocery service surfaces it (AI navigator cost-cap / outage) and it maps to 503 here.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GroceryExceptionHandler {

  @ExceptionHandler(ShoppingListNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleShoppingListNotFound(
      ShoppingListNotFoundException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.NOT_FOUND,
        ex.getMessage(),
        "shopping-list-not-found",
        "Shopping list not found",
        req);
  }

  @ExceptionHandler(ShoppingListLineNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleShoppingListLineNotFound(
      ShoppingListLineNotFoundException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.NOT_FOUND,
        ex.getMessage(),
        "shopping-list-line-not-found",
        "Shopping list line not found",
        req);
  }

  @ExceptionHandler(GroceryOrderNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleGroceryOrderNotFound(
      GroceryOrderNotFoundException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.NOT_FOUND,
        ex.getMessage(),
        "grocery-order-not-found",
        "Grocery order not found",
        req);
  }

  @ExceptionHandler(GrocerySubstitutionProposalNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleSubstitutionProposalNotFound(
      GrocerySubstitutionProposalNotFoundException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.NOT_FOUND,
        ex.getMessage(),
        "grocery-substitution-proposal-not-found",
        "Grocery substitution proposal not found",
        req);
  }

  @ExceptionHandler(IllegalOrderTransitionException.class)
  public ResponseEntity<ProblemDetail> handleIllegalOrderTransition(
      IllegalOrderTransitionException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "illegal-order-transition",
            "Illegal order transition",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("from", ex.from());
      pd.setProperty("to", ex.to());
    }
    return response;
  }

  @ExceptionHandler(OrderHasOutstandingProposalsException.class)
  public ResponseEntity<ProblemDetail> handleOrderHasOutstandingProposals(
      OrderHasOutstandingProposalsException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "order-has-outstanding-proposals",
            "Order has outstanding substitution proposals",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("orderId", ex.orderId());
      pd.setProperty("outstandingCount", ex.outstandingCount());
    }
    return response;
  }

  @ExceptionHandler(ProviderNotConfiguredException.class)
  public ResponseEntity<ProblemDetail> handleProviderNotConfigured(
      ProviderNotConfiguredException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "provider-not-configured",
            "Provider not configured",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("providerKey", ex.providerKey());
    }
    return response;
  }

  @ExceptionHandler(ProviderUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleProviderUnavailable(
      ProviderUnavailableException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            ex.getMessage(),
            "provider-unavailable",
            "Provider unavailable",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("providerKey", ex.providerKey());
      pd.setProperty("reason", ex.reason());
    }
    return response;
  }

  @ExceptionHandler(OrderConcurrencyConflictException.class)
  public ResponseEntity<ProblemDetail> handleOrderConcurrencyConflict(
      OrderConcurrencyConflictException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.CONFLICT,
        ex.getMessage(),
        "order-concurrency-conflict",
        "Order concurrency conflict",
        req);
  }

  @ExceptionHandler(LineAlreadyBoughtException.class)
  public ResponseEntity<ProblemDetail> handleLineAlreadyBought(
      LineAlreadyBoughtException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "line-already-bought",
            "Shopping list line already bought",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("shoppingListLineId", ex.shoppingListLineId());
    }
    return response;
  }

  @ExceptionHandler(LineNotBoughtException.class)
  public ResponseEntity<ProblemDetail> handleLineNotBought(
      LineNotBoughtException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "line-not-bought",
            "Shopping list line not bought",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("shoppingListLineId", ex.shoppingListLineId());
      pd.setProperty("currentStatus", ex.currentStatus());
    }
    return response;
  }

  /**
   * The provisions idempotency log rejected a re-applied grocery-import ({@code (userId, source,
   * sourceRef)} — the shopping-list-line-id used as {@code orderRef} on a mark-bought retry). The
   * inventory was NOT double-added; surface 409 so the frontend learns the line was already
   * fulfilled and refreshes.
   */
  @ExceptionHandler(DuplicateGroceryImportException.class)
  public ResponseEntity<ProblemDetail> handleDuplicateGroceryImport(
      DuplicateGroceryImportException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.CONFLICT,
        ex.getMessage(),
        "duplicate-grocery-import",
        "Grocery import already applied",
        req);
  }

  @ExceptionHandler(AiUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleAiUnavailable(
      AiUnavailableException ex, HttpServletRequest req) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), "ai-unavailable", "AI unavailable", req);
  }

  @ExceptionHandler(UnknownMappingKeyException.class)
  public ResponseEntity<ProblemDetail> handleUnknownMappingKey(
      UnknownMappingKeyException ex, HttpServletRequest req) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "unknown-mapping-key",
            "Unknown ingredient mapping key",
            req);
    ProblemDetail pd = response.getBody();
    if (pd != null) {
      pd.setProperty("ingredientMappingKey", ex.ingredientMappingKey());
    }
    return response;
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String detail, String typeSlug, String title, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(status, detail, typeSlug, title, req.getRequestURI());
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }
}
