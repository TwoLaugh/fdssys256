package com.example.mealprep.planner.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * JSONB carrier persisted on {@code MealPrepPlanReoptSuggestion.proposedAssignments}. Holds the
 * materialised slot-level diff the {@code MidWeekReoptCoordinator} (planner-01i) computed between
 * the original active plan and the Stage-C-chosen re-opt plan, plus a {@code schemaVersion} root
 * field per {@code style-guide.md §JSONB} (increment on any non-additive shape change; the
 * round-trip test guards drift).
 *
 * <p>Read whole by 01j's accept endpoint when promoting the suggestion onto the live plan; never
 * filtered on inner fields and nothing FKs into it — JSONB is the right carrier here.
 */
public record ProposedReoptAssignmentsDocument(
    int schemaVersion, List<ProposedSlotChange> changes) {

  /** Current document shape version. Bump on any non-additive change to the nested records. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ProposedReoptAssignmentsDocument {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }

  public static ProposedReoptAssignmentsDocument of(List<ProposedSlotChange> changes) {
    return new ProposedReoptAssignmentsDocument(CURRENT_SCHEMA_VERSION, changes);
  }

  /**
   * One proposed swap. {@code oldRecipeId} may be {@code null} if the original slot had no
   * scheduled recipe (an eating-out / fasting slot the re-opt now fills).
   */
  public record ProposedSlotChange(
      UUID slotId,
      UUID oldRecipeId,
      UUID newRecipeId,
      UUID newRecipeVersionId,
      UUID newRecipeBranchId,
      int newServings,
      String reason) {}
}
