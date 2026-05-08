package com.example.mealprep.ai.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Aggregated cost view returned by the admin {@code /cost-summary} endpoint. {@code
 * totalMicroPence} is the sum across all callers in the window; {@code topUsers} carries the top 20
 * spenders with their per-user rollups (call count + cost).
 *
 * <p>Cost calculation lands in 01b — for 01a-only deployments every row's {@code costMicroPence} is
 * {@code 0}, but the shape is stable.
 */
public record CostSummaryDto(
    int windowHours, long totalCalls, long totalMicroPence, List<UserCostEntry> topUsers) {

  public record UserCostEntry(UUID userId, long calls, long costMicroPence) {}
}
