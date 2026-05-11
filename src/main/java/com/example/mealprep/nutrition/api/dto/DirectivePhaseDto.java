package com.example.mealprep.nutrition.api.dto;

/** Single phase in a staged-protocol duration. */
public record DirectivePhaseDto(String phase, Integer durationWeeks, String rule) {}
