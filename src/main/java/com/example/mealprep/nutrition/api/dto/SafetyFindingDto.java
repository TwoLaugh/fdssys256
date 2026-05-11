package com.example.mealprep.nutrition.api.dto;

/**
 * Single safety-gate finding. {@code code} is a short kebab-case identifier for UI mapping (e.g.
 * {@code target-raise-exceeds-20pct}); {@code severity} ∈ {@code BLOCK | WARN | INFO}.
 */
public record SafetyFindingDto(String code, String message, String severity) {}
