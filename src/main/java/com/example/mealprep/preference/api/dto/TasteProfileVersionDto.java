package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-delta-batch snapshot of a taste profile document. Returned by the versions list and
 * by-version endpoints. {@code deltasApplied} is the raw delta payload as JSON; consumers typing
 * each delta to {@code TasteProfileDelta} is their responsibility.
 */
public record TasteProfileVersionDto(
    UUID id,
    UUID tasteProfileId,
    int documentVersion,
    TasteProfileDocument documentSnapshot,
    String feedbackRangeStart,
    String feedbackRangeEnd,
    TasteProfileTrigger trigger,
    JsonNode deltasApplied,
    String modelTierUsed,
    Instant generatedAt) {}
