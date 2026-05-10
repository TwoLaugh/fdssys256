package com.example.mealprep.nutrition.api.dto;

import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.util.UUID;

/** Read shape of a single intake slot row. */
public record IntakeSlotDto(
    UUID id, MealSlot mealSlot, PlannedIntakeDto planned, ActualIntakeDto actual) {}
