package com.example.mealprep.recipe.event;

/**
 * Why a recipe was archived. Carried by {@link RecipeArchivedEvent}. Per LLD line 698 / recipe-01g
 * ticket §Archive (manual) and §ArchiveEligibilityScanner.
 *
 * <ul>
 *   <li>{@link #MANUAL_ADMIN} — explicit caller-triggered archive via {@code POST /archive}.
 *   <li>{@link #INACTIVITY_3_MONTHS} — daily scanner flagged a SYSTEM-catalogue row whose {@code
 *       last_used_in_plan_at} is older than 90 days (or null = never used).
 *   <li>{@link #USER_DEMOTION} — published when a USER-catalogue recipe is demoted back to SYSTEM
 *       (LLD line 764).
 * </ul>
 */
public enum ArchiveCause {
  MANUAL_ADMIN,
  INACTIVITY_3_MONTHS,
  USER_DEMOTION
}
