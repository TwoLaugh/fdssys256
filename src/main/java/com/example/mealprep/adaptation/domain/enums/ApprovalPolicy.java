package com.example.mealprep.adaptation.domain.enums;

/**
 * Where the apply-stage result lands. {@code PENDING_CHANGE} requires user approval (USER
 * catalogue); {@code DIRECT} auto-applies via {@code RecipeWriteApi} (SYSTEM catalogue); {@code
 * PLAN_OVERLAY} writes a per-slot substitution for Trigger 4. Verbatim from {@code
 * lld/adaptation-pipeline.md} line 90.
 */
public enum ApprovalPolicy {
  PENDING_CHANGE,
  DIRECT,
  PLAN_OVERLAY
}
