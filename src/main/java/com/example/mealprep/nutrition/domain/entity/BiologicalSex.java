package com.example.mealprep.nutrition.domain.entity;

/**
 * Biological sex for guideline target computation — selects the Mifflin-St Jeor BMR constant and the
 * {@code (age_group, sex)} DRI micronutrient band. Distinct from gender identity; this is purely the
 * physiological input the nutrition reference values are defined against (NIH/IOM DRIs are
 * sex-specific). {@code FEMALE}/{@code MALE} map to the seed's {@code sex} column values.
 */
public enum BiologicalSex {
  FEMALE,
  MALE
}
