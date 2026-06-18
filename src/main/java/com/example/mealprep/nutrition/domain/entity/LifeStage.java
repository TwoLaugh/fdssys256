package com.example.mealprep.nutrition.domain.entity;

/**
 * Reproductive life-stage, which materially changes several micronutrient floors (folate, iron,
 * iodine, vitamin A, choline, …). Only meaningful for {@link BiologicalSex#FEMALE} in the
 * reproductive age bands; the guideline service falls back to {@code NONE} otherwise. Maps to the
 * {@code life_stage} column of {@code nutrition_dri_defaults}.
 */
public enum LifeStage {
  NONE,
  PREGNANT,
  LACTATING
}
