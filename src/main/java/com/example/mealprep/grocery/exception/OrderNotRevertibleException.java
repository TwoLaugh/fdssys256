package com.example.mealprep.grocery.exception;

import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import java.util.UUID;

/**
 * A back-to-draft was requested on an order that is not currently {@code QUOTED} — the only state
 * the re-edit revert is offered from (grocery-provisions-p3-clarifications item 1). Mapped to 422
 * by {@code GroceryExceptionHandler}: the request shape is fine, the order is just not in a
 * revertible state.
 */
public class OrderNotRevertibleException extends GroceryException {

  private final UUID orderId;
  private final GroceryOrderStatus currentStatus;

  public OrderNotRevertibleException(UUID orderId, GroceryOrderStatus currentStatus) {
    super(
        "Grocery order '"
            + orderId
            + "' cannot revert to draft from "
            + currentStatus
            + " — only a QUOTED order can be re-edited.");
    this.orderId = orderId;
    this.currentStatus = currentStatus;
  }

  public UUID orderId() {
    return orderId;
  }

  public GroceryOrderStatus currentStatus() {
    return currentStatus;
  }
}
