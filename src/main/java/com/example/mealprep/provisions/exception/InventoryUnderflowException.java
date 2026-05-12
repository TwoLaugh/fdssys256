package com.example.mealprep.provisions.exception;

import com.example.mealprep.provisions.api.dto.UnderflowFlagDto;
import java.util.List;

/**
 * Thrown by {@code applyCookEvent} in strict mode when the pantry cannot cover the requested
 * deduction for one or more ingredients. Mapped to HTTP 422 by {@code ProvisionsExceptionHandler}.
 */
public class InventoryUnderflowException extends ProvisionsException {

  private final List<UnderflowFlagDto> underflows;

  public InventoryUnderflowException(List<UnderflowFlagDto> underflows) {
    super(
        "Insufficient inventory for cook event: "
            + (underflows == null ? 0 : underflows.size())
            + " ingredient(s) underflowed.");
    this.underflows = underflows == null ? List.of() : List.copyOf(underflows);
  }

  public List<UnderflowFlagDto> getUnderflows() {
    return underflows;
  }
}
