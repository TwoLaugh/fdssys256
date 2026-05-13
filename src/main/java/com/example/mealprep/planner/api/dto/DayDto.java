package com.example.mealprep.planner.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One day inside a {@link PlanDto}. Slots are ordered by {@code slotIndex} ascending. */
public record DayDto(UUID id, LocalDate date, String notes, List<MealSlotDto> slots) {}
