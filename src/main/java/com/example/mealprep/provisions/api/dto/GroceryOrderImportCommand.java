package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.validation.PastOrNextDay;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/provisions/grocery-import}. LLD line 458-465 verbatim. The
 * {@code userId} is resolved server-side (caller's session) and is NOT carried on this record.
 * Idempotent on {@code (userId, supplier, orderRef)} — a duplicate yields HTTP 409.
 */
public record GroceryOrderImportCommand(
    @NotBlank @Size(max = 32) String supplier,
    @NotBlank @Size(max = 128) String orderRef,
    @NotNull @PastOrNextDay LocalDate deliveredOn,
    @NotEmpty @Valid List<GroceryOrderLine> lines,
    @Nullable @Valid List<GroceryOrderSubstitution> substitutions,
    @Nullable UUID traceId) {}
