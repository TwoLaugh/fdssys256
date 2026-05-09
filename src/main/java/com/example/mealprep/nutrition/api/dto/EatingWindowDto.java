package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/**
 * Optional eating-window. When {@code enabled} is false the start / end fields are typically null;
 * the cross-field {@code @ValidEatingWindow} validator that enforces the relationship lands in a
 * later sub-ticket.
 */
public record EatingWindowDto(
    boolean enabled, LocalTime windowStart, LocalTime windowEnd, @Size(max = 255) String notes) {}
