package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.validation.ValidWasteDateRange;
import java.time.LocalDate;

/**
 * Wrapper for the {@code GET /waste} and {@code GET /waste/summary} query parameters so that the
 * class-level {@link ValidWasteDateRange} constraint can fire. Either field may be null on the list
 * endpoint (defaults applied controller-side); both are required on the summary endpoint.
 */
@ValidWasteDateRange
public record WasteListQuery(LocalDate from, LocalDate to) {}
