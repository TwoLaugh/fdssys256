package com.example.mealprep.planner.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One detected constraint conflict (planner-6, LLD §Constraint feasibility DTOs). Carries the
 * {@link ConflictType} classification, the slot ids it affects (the slots whose post-hard-filter
 * pool fell below the planning minimum), and a human-readable description for the resolution
 * dialog.
 */
public record ConstraintConflictDto(
    ConflictType type, List<UUID> affectedSlotIds, String description) {}
