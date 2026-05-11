package com.example.mealprep.provisions.event;

/**
 * Why an inventory item's quantity was adjusted. Carried on {@link ItemQuantityAdjustedEvent} so
 * downstream listeners (planner, analytics) can disambiguate user-driven flows.
 *
 * <p>LLD line 568 declares this enum's {@code WASTE} value as the canonical source for 01e's
 * waste-deduction flow. Sibling values land with their flows (cook-event 01g, grocery-import 01h).
 */
public enum ItemAdjustmentSource {
  WASTE,
  COOK_EVENT,
  GROCERY_IMPORT,
  USER_EDIT
}
