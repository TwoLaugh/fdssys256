package com.example.mealprep.adaptation.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * One ingredient-pairing fact ("iron-rich + lemon = boosted absorption").
 *
 * <p><b>LLD divergence</b>: LLD line 547 punts on the shape. 01b ships a minimal record with a
 * {@code JsonNode payload} carrier so the v1 lookup-table impl (01e) can fill it from the {@code
 * adaptation_nutritional_knowledge} rows seeded by repeatable migration. <b>Worth user review</b> —
 * the shape can be tightened once the v1 knowledge table is seeded.
 *
 * @param subjectKeys ingredient mapping keys this pairing fact applies to
 * @param description short user-facing description, e.g. "lemon boosts non-haem iron absorption"
 * @param confidence 0.0..1.0 evidence-strength score
 * @param payload opaque structured details (cite, magnitude, conditions) — shape per v1 table
 */
public record NutritionalPairingDto(
    List<String> subjectKeys, String description, BigDecimal confidence, JsonNode payload) {}
