package com.example.mealprep.nutrition.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read shape of an intake day aggregate. {@code version} reflects the JPA {@code @Version}. */
public record IntakeDayDto(
    UUID id,
    UUID userId,
    LocalDate onDate,
    UUID planId,
    List<IntakeSlotDto> slots,
    List<IntakeSnackDto> snacks,
    long version) {}
