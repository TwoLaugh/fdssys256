package com.example.mealprep.provisions.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Wire shape of a single substitution event — appended to {@code
 * SupplierProduct.substitutionHistory}. {@code notes} is the only nullable field.
 */
public record SubstitutionRecordDto(
    @NotNull LocalDate date,
    @NotBlank @Size(max = 128) String substitutedWithProductId,
    boolean accepted,
    @Size(max = 1000) String notes) {}
