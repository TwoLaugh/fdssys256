package com.example.mealprep.grocery.api.dto;

/** Result of a Tier-4 on-demand / scheduled price refresh. Per lld/grocery.md lines 466-467. */
public record RefreshPricesResultDto(
    int observationsWritten,
    int ingredientsRefreshed,
    boolean aiUnavailableFallbackUsed,
    String fallbackMessage) {}
