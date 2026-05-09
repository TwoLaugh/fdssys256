package com.example.mealprep.provisions.domain.entity;

/**
 * Soft-delete-with-reason for an inventory row. Active items show in lists; the others persist for
 * audit / waste-log FK validity until the retention sweep (01k) hard-deletes them.
 */
public enum ItemLifecycleStatus {
  ACTIVE,
  EXHAUSTED,
  SPOILED,
  WASTED
}
