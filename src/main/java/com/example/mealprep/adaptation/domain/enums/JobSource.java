package com.example.mealprep.adaptation.domain.enums;

/**
 * Source of an {@code AdaptationJob} — drives priority defaults, approval policy, and the Stage A
 * candidate bias. Verbatim from {@code lld/adaptation-pipeline.md} line 88.
 */
public enum JobSource {
  IMPORT,
  FEEDBACK,
  DATA_MODEL_CHANGE,
  PLAN_TIME
}
