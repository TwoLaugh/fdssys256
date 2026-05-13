package com.example.mealprep.planner.domain.entity;

/**
 * Per-slot state machine value. State transitions land in 01j ({@code markSlotState}); 01a only
 * defines the enum and uses {@code PLANNED} for fixture data.
 */
public enum SlotState {
  PLANNED,
  COOKING,
  COOKED,
  EATEN,
  SKIPPED
}
