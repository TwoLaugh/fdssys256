package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.api.ProvisionsExceptionHandler;
import com.example.mealprep.provisions.api.dto.UnderflowFlagDto;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.exception.BatchCookNotSupportedException;
import com.example.mealprep.provisions.exception.BudgetCurrencyChangeException;
import com.example.mealprep.provisions.exception.BudgetNotFoundException;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import com.example.mealprep.provisions.exception.EquipmentNotFoundException;
import com.example.mealprep.provisions.exception.InvalidInventoryQuantityException;
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import com.example.mealprep.provisions.exception.InventoryUnderflowException;
import com.example.mealprep.provisions.exception.SupplierProductNotFoundException;
import com.example.mealprep.provisions.exception.WasteExceedsInventoryException;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
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
 * Unit tests for {@link ProvisionsExceptionHandler}. Each handler is invoked directly with its
 * exception + a mock request; assertions pin the HTTP status, the {@code application/problem+json}
 * content type, and the ProblemDetail detail / title / type-slug / instance plus any extension
 * properties set via {@code setProperty}. This kills the per-handler status-constant,
 * string-literal and {@code setProperty}-key/value mutants (all previously uncovered — handler was
 * ~1% covered). Pure — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ProvisionsExceptionHandlerTest {

  private static final String URI = "/api/v1/provisions/whatever";

  @Mock private HttpServletRequest request;
  private final ProvisionsExceptionHandler handler = new ProvisionsExceptionHandler();

  @BeforeEach
  void stubUri() {
    when(request.getRequestURI()).thenReturn(URI);
  }

  private void assertProblem(
      ResponseEntity<ProblemDetail> resp,
      HttpStatus status,
      String typeSlug,
      String title,
      String detailContains) {
    assertThat(resp.getStatusCode()).isEqualTo(status);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(status.value());
    assertThat(pd.getTitle()).isEqualTo(title);
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/" + typeSlug);
    assertThat(pd.getInstance().toString()).isEqualTo(URI);
    assertThat(pd.getDetail()).contains(detailContains);
  }

  @Test
  void inventoryItemNotFound_maps_to_404() {
    UUID itemId = UUID.randomUUID();
    var resp =
        handler.handleInventoryItemNotFound(new InventoryItemNotFoundException(itemId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "inventory-item-not-found",
        "Inventory item not found",
        itemId.toString());
  }

  @Test
  void invalidInventoryQuantity_maps_to_400() {
    var resp =
        handler.handleInvalidInventoryQuantity(
            new InvalidInventoryQuantityException("quantity must be >= 0"), request);
    assertProblem(
        resp,
        HttpStatus.BAD_REQUEST,
        "invalid-inventory-quantity",
        "Invalid inventory quantity",
        "quantity must be >= 0");
  }

  @Test
  void equipmentNotFound_maps_to_404() {
    UUID userId = UUID.randomUUID();
    var resp =
        handler.handleEquipmentNotFound(
            new EquipmentNotFoundException(userId, "Air Fryer"), request);
    assertProblem(
        resp, HttpStatus.NOT_FOUND, "equipment-not-found", "Equipment not found", "Air Fryer");
  }

  @Test
  void budgetNotFound_maps_to_404() {
    UUID userId = UUID.randomUUID();
    var resp = handler.handleBudgetNotFound(new BudgetNotFoundException(userId), request);
    assertProblem(
        resp, HttpStatus.NOT_FOUND, "budget-not-found", "Budget not found", userId.toString());
  }

  @Test
  void budgetCurrencyChange_maps_to_422() {
    var resp =
        handler.handleBudgetCurrencyChange(
            new BudgetCurrencyChangeException("cannot switch GBP -> USD"), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "budget-currency-change-rejected",
        "Budget currency change rejected",
        "cannot switch GBP -> USD");
  }

  @Test
  void supplierProductNotFound_maps_to_404() {
    UUID id = UUID.randomUUID();
    var resp =
        handler.handleSupplierProductNotFound(new SupplierProductNotFoundException(id), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "supplier-product-not-found",
        "Supplier product not found",
        id.toString());
  }

  @Test
  void wasteExceedsInventory_maps_to_422_with_extension_properties() {
    UUID itemId = UUID.randomUUID();
    BigDecimal requested = new BigDecimal("5.50");
    BigDecimal remaining = new BigDecimal("2.00");
    var resp =
        handler.handleWasteExceedsInventory(
            new WasteExceedsInventoryException(itemId, requested, remaining), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "waste-exceeds-inventory",
        "Waste quantity exceeds remaining inventory",
        itemId.toString());
    assertThat(resp.getBody().getProperties())
        .containsEntry("inventoryItemId", itemId)
        .containsEntry("requested", requested)
        .containsEntry("remaining", remaining);
  }

  @Test
  void inventoryUnderflow_maps_to_422_with_underflows_property() {
    List<UnderflowFlagDto> underflows =
        List.of(new UnderflowFlagDto("flour", new BigDecimal("3"), new BigDecimal("1")));
    var resp =
        handler.handleInventoryUnderflow(new InventoryUnderflowException(underflows), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "inventory-underflow",
        "Insufficient inventory for cook event",
        "1 ingredient(s) underflowed");
    assertThat(resp.getBody().getProperties()).containsEntry("underflows", underflows);
  }

  @Test
  void batchCookNotSupported_maps_to_422() {
    var resp = handler.handleBatchCookNotSupported(new BatchCookNotSupportedException(), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "batch-cook-not-supported",
        "Batch cook is not supported in v1",
        "Batch cook is not supported");
  }

  @Test
  void duplicateGroceryImport_maps_to_409_with_extension_properties() {
    UUID userId = UUID.randomUUID();
    var resp =
        handler.handleDuplicateGroceryImport(
            new DuplicateGroceryImportException(userId, ItemSource.TESCO_ORDER, "order-123"),
            request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "duplicate-grocery-import",
        "Duplicate grocery import",
        "order-123");
    assertThat(resp.getBody().getProperties())
        .containsEntry("userId", userId)
        .containsEntry("source", ItemSource.TESCO_ORDER)
        .containsEntry("sourceRef", "order-123");
  }
}
