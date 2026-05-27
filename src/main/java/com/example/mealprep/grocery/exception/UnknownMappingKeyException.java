package com.example.mealprep.grocery.exception;

/**
 * Thrown by the Tier-4 write path (01c) when a price observation is recorded for an {@code
 * ingredient_mapping_key} that, after normalisation, is blank / unresolvable. Maps to HTTP 400 —
 * the caller supplied an invalid key. Per the edge-case checklist "unknown mapping key rejected".
 */
public class UnknownMappingKeyException extends GroceryException {

  private final String ingredientMappingKey;

  public UnknownMappingKeyException(String ingredientMappingKey) {
    super("Unknown or blank ingredient mapping key: '" + ingredientMappingKey + "'");
    this.ingredientMappingKey = ingredientMappingKey;
  }

  public String ingredientMappingKey() {
    return ingredientMappingKey;
  }
}
