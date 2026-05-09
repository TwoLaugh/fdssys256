package com.example.mealprep.preference.api.dto;

import java.util.List;

/**
 * Outcome of one hard-constraint filter check. {@code passes} is the convenience boolean; {@code
 * violations} carries every reason the check failed (not just the first), so the planner UI can
 * surface the full list to the user.
 */
public record FilterResult(boolean passes, List<Violation> violations) {}
