package com.example.mealprep.preference.domain.entity;

/**
 * Why a taste-profile audit row was written. {@code INITIALIZED} fires once on first creation (no
 * {@code previousDocumentVersion}); {@code MANUAL_OVERRIDE} is the PUT endpoint; {@code
 * AI_DELTA_APPLIED} is the (deferred) delta pipeline; {@code REFRESH_TRIGGERED} is the POST {@code
 * /refresh-now} fire-and-forget call; {@code ROLLED_BACK} is the (deferred) rollback endpoint.
 */
public enum TasteProfileChangeType {
  INITIALIZED,
  MANUAL_OVERRIDE,
  AI_DELTA_APPLIED,
  REFRESH_TRIGGERED,
  ROLLED_BACK
}
