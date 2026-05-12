package com.example.mealprep.provisions.event;

/**
 * Why an inventory item's quantity was adjusted. Carried on {@link ItemQuantityAdjustedEvent} so
 * downstream listeners (planner, analytics) can disambiguate user-driven flows.
 *
 * <p>LLD line 568 declares this enum's values. {@code WASTE} is published from the 01e waste-log
 * flow; {@code COOK_EVENT}/{@code MEAL_CONSUMPTION}/{@code STANDALONE_LOG} from the 01g
 * cook/consumption flows; {@code GROCERY_IMPORT} from 01h; {@code USER_EDIT}/{@code MANUAL} from
 * direct PATCH/PUT mutations.
 */
public enum ItemAdjustmentSource {
  WASTE,
  COOK_EVENT,
  MEAL_CONSUMPTION,
  STANDALONE_LOG,
  GROCERY_IMPORT,
  USER_EDIT,
  MANUAL
}
