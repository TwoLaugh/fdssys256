package com.example.mealprep.nutrition.exception;

/**
 * Thrown when an accept-flow finds a {@code mapsToModel} the applier doesn't know how to route.
 * Only legitimate values are {@code nutrition_model} and {@code preference_model}. Mapped to HTTP
 * 422.
 */
public class InvalidDirectiveRoutingException extends NutritionException {

  private final String mapsToModel;

  public InvalidDirectiveRoutingException(String mapsToModel) {
    super("Invalid directive routing: mapsToModel=" + mapsToModel);
    this.mapsToModel = mapsToModel;
  }

  public String mapsToModel() {
    return mapsToModel;
  }
}
