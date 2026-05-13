package com.example.mealprep.adaptation.domain.enums;

/**
 * Kind of {@code NutritionalKnowledgeEntry}. Drives the GIN-intersect query in {@code
 * NutritionalKnowledgeRepository.findIntersectingSubjects}. Verbatim from {@code
 * lld/adaptation-pipeline.md} line 251.
 */
public enum KnowledgeKind {
  PAIRING,
  METHOD_BIOAVAILABILITY,
  SOAK_NEEDED,
  ABSORPTION_CONFLICT
}
