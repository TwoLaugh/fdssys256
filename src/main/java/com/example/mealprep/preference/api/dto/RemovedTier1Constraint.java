package com.example.mealprep.preference.api.dto;

/**
 * One safety-critical Tier-1 hard constraint that an {@code UpdateHardConstraintsRequest} would
 * remove, surfaced in the {@code TIER1_REMOVAL_REQUIRES_CONFIRMATION} rejection so the UI can name
 * exactly what the user is about to drop in the confirmation interstitial (GAP-04).
 *
 * <p>{@code category} is the machine-readable Tier-1 kind ({@code ALLERGY}, {@code MEDICAL_DIET},
 * {@code SEVERE_INTOLERANCE}, {@code DIETARY_IDENTITY_BASE}); {@code value} is the human-readable
 * item being removed (the allergen / diet / intolerance substance, or — for a dietary-identity
 * narrowing — the previous base that is being changed away from).
 */
public record RemovedTier1Constraint(Tier1Category category, String value) {}
