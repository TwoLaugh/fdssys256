package com.example.mealprep.planner.domain.service.internal.stagec;

import java.util.UUID;

/**
 * Flag a known issue with a human-readable resolution (e.g. "this slot's protein is below the
 * per-person floor"). Per lld/planner.md §{@code Phase2Augmenter} (planner-01h). No mutation; the
 * verifier always passes a repair (there is no constraint to check — it is purely informational).
 *
 * <p>{@code targetSlotId} is {@code null} for a plan-level repair (an issue spanning the whole week
 * rather than one slot). The {@code resolution} field plays the role of {@code reasoning} for this
 * variant.
 */
public record RepairAugmentation(UUID targetSlotId, String issue, String resolution)
    implements Augmentation {}
