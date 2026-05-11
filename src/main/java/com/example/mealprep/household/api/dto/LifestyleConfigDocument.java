package com.example.mealprep.household.api.dto;

/**
 * Lightweight stub shape for the household merge surface. Preference-01c will own the canonical
 * record; until then this record lets the merge interface validate against a real type rather than
 * {@code Object}. See LLD divergence note in 01e's ticket.
 *
 * @param mealTimingWindowStart {@code HH:mm}; nullable
 * @param mealTimingWindowEnd {@code HH:mm}; nullable
 * @param noveltyTolerancePercent 0-100; nullable
 * @param batchCookingPreferred primitive — defaults to {@code false}
 */
public record LifestyleConfigDocument(
    String mealTimingWindowStart,
    String mealTimingWindowEnd,
    Integer noveltyTolerancePercent,
    boolean batchCookingPreferred) {}
