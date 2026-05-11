package com.example.mealprep.nutrition.api.dto;

/** Health-directive classifier per LLD §Entities line 300. */
public enum DirectiveType {
  INGREDIENT_RESTRICTION,
  TARGET_ADJUSTMENT,
  MACRO_REBALANCE,
  ELIMINATION_TRIAL,
  REINTRODUCTION_PROTOCOL,
  SENSITIVITY_DOWNGRADE
}
