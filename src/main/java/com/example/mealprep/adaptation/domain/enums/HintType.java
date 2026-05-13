package com.example.mealprep.adaptation.domain.enums;

/**
 * Kind of {@code PlannerHintRecord} emitted by an adaptation job. Drives the
 * {@code @ValidPlannerHint} payload-shape validator (defined in 01d). Verbatim from {@code
 * lld/adaptation-pipeline.md} line 224.
 */
public enum HintType {
  PREP_LEAD_TIME,
  ABSORPTION_CONFLICT,
  NUTRITION_TRADEOFF,
  EQUIPMENT_OVERLAP,
  BATCH_COMPATIBILITY
}
