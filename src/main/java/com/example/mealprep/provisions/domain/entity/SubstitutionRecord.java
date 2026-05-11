package com.example.mealprep.provisions.domain.entity;

import java.time.LocalDate;

/**
 * One element of {@code SupplierProduct.substitutionHistory} — captures a single substitution event
 * (the supplier delivered a different SKU; the user accepted or rejected the swap). Stored as a
 * JSON array element on the parent's {@code substitution_history} JSONB column; never queried by
 * sub-field (always read whole).
 *
 * <p>{@code notes} is nullable for the common "no comment" path; the others are required.
 */
public record SubstitutionRecord(
    LocalDate date, String substitutedWithProductId, boolean accepted, String notes) {}
