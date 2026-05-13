package com.example.mealprep.feedback.domain.document;

import com.example.mealprep.feedback.api.dto.Screen;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Storage shape of {@code feedback_entries.ui_context}. Mirrors {@code UiContextDto}'s field set;
 * lives in {@code domain/document/} because it is the persistence representation (Jackson
 * serialises to/from the JSONB column), distinct from the wire DTO.
 */
public record UiContextDocument(
    Screen screen,
    UUID recipeId,
    Integer recipeVersion,
    UUID mealSlotId,
    UUID planId,
    LocalDate referenceDate) {}
