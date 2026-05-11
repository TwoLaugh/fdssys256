package com.example.mealprep.provisions.exception;

import java.util.UUID;

/**
 * Thrown when a supplier-product row cannot be located by id. Mapped to HTTP 404 — supplier
 * products are global reference data, so missing genuinely means missing (no per-user existence
 * leak concern).
 */
public class SupplierProductNotFoundException extends ProvisionsException {

  private final UUID supplierProductId;

  public SupplierProductNotFoundException(UUID supplierProductId) {
    super("Supplier product not found: " + supplierProductId);
    this.supplierProductId = supplierProductId;
  }

  public UUID supplierProductId() {
    return supplierProductId;
  }
}
