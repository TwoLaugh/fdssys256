package com.example.mealprep.planner.api.dto;

/**
 * Lifecycle of an asynchronous plan-generation job (async generate endpoint). {@code RUNNING} while
 * the composer is executing on a background worker; {@code COMPLETED} once a plan was persisted
 * (the job then carries its {@code planId}); {@code FAILED} if composition threw (the job then
 * carries a short {@code errorCode}). An idempotency-key replay short-circuits straight to {@code
 * COMPLETED} without scheduling work.
 */
public enum PlanGenerationStatus {
  RUNNING,
  COMPLETED,
  FAILED
}
