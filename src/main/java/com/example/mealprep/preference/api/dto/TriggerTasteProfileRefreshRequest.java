package com.example.mealprep.preference.api.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;

/**
 * Body for the POST {@code /refresh-now} endpoint. Both range fields are optional — if omitted, the
 * feedback module's delta task picks up from "since last cursor". The endpoint is fire-and-forget
 * at the controller layer: it publishes a {@code TasteProfileRefreshRequestedEvent} and returns the
 * current state.
 */
public record TriggerTasteProfileRefreshRequest(
    @Nullable @Size(max = 64) String feedbackRangeStart,
    @Nullable @Size(max = 64) String feedbackRangeEnd) {}
