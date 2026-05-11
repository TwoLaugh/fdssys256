package com.example.mealprep.provisions.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.provisions.exception.BudgetCurrencyChangeException;
import com.example.mealprep.provisions.exception.BudgetNotFoundException;
import com.example.mealprep.provisions.exception.EquipmentNotFoundException;
import com.example.mealprep.provisions.exception.InvalidInventoryQuantityException;
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import com.example.mealprep.provisions.exception.SupplierProductNotFoundException;
import com.example.mealprep.provisions.exception.WasteExceedsInventoryException;
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
 * Provisions-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProvisionsExceptionHandler {

  @ExceptionHandler(InventoryItemNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleInventoryItemNotFound(
      InventoryItemNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "inventory-item-not-found",
            "Inventory item not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidInventoryQuantityException.class)
  public ResponseEntity<ProblemDetail> handleInvalidInventoryQuantity(
      InvalidInventoryQuantityException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "invalid-inventory-quantity",
            "Invalid inventory quantity",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(EquipmentNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleEquipmentNotFound(
      EquipmentNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "equipment-not-found",
            "Equipment not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(BudgetNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleBudgetNotFound(
      BudgetNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "budget-not-found",
            "Budget not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(BudgetCurrencyChangeException.class)
  public ResponseEntity<ProblemDetail> handleBudgetCurrencyChange(
      BudgetCurrencyChangeException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "budget-currency-change-rejected",
            "Budget currency change rejected",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(SupplierProductNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleSupplierProductNotFound(
      SupplierProductNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "supplier-product-not-found",
            "Supplier product not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(WasteExceedsInventoryException.class)
  public ResponseEntity<ProblemDetail> handleWasteExceedsInventory(
      WasteExceedsInventoryException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "waste-exceeds-inventory",
            "Waste quantity exceeds remaining inventory",
            req.getRequestURI());
    pd.setProperty("inventoryItemId", ex.getInventoryItemId());
    pd.setProperty("requested", ex.getRequested());
    pd.setProperty("remaining", ex.getRemaining());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
