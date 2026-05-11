package com.example.mealprep.nutrition.api.dto;

import java.util.List;

/**
 * Duration shape for a directive — flat single-window or {@code staged_protocol} with phased rules.
 */
public record DirectiveDurationDto(
    String type, List<DirectivePhaseDto> phases, Integer durationWeeks) {}
