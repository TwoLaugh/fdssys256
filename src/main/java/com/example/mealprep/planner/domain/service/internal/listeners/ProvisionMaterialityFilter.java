package com.example.mealprep.planner.domain.service.internal.listeners;

import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.provisions.event.ItemAddedFromGroceryEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.event.ProvisionChangedEvent;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a {@link ProvisionChangedEvent} materially affects a plan (planner-01k invariant
 * #3 / #9). Package-private {@code @Component} — only {@link PlannerEventListener} (same package)
 * injects it; cross-module isolation is asserted by the planner ArchUnit boundary test.
 *
 * <p>Materiality rules, per ticket §3 + §Trigger-filters table:
 *
 * <ul>
 *   <li>{@link ItemAddedFromGroceryEvent} (stocking up) — NEVER material. Adding stock can only
 *       improve a plan's feasibility, never invalidate it; surfacing a re-opt would be thrash.
 *   <li>{@link ItemRanOutEvent} — material if ANY non-pinned remaining slot's recipe lists the
 *       event's {@code ingredientMappingKey} as an ingredient. This is the only variant that
 *       carries the mapping key directly, so it is the only one we can match precisely.
 *   <li>Spoiled / quantity-adjusted / substitution-accepted / generic — the event shape carries
 *       only inventory-row ids (not ingredient mapping keys), so a precise recipe match is
 *       impossible here. We conservatively treat these as material when the plan still has a
 *       non-pinned remaining slot (over-trigger; the ticket explicitly prefers over- to
 *       under-triggering re-opt). If the plan has no degrees of freedom the coordinator's no-DoF
 *       guard makes it a cheap no-op anyway.
 * </ul>
 *
 * <p>"Remaining, non-pinned" = a slot whose state is still {@link SlotState#PLANNED} (not yet
 * cooked/eaten/skipped) — a slot the user could still regenerate. Cooked/eaten slots are immutable
 * history; an inventory change cannot retroactively affect them.
 */
@Component
class ProvisionMaterialityFilter {

  private static final Logger log = LoggerFactory.getLogger(ProvisionMaterialityFilter.class);

  private final RecipeQueryService recipeQueryService;

  ProvisionMaterialityFilter(RecipeQueryService recipeQueryService) {
    this.recipeQueryService = recipeQueryService;
  }

  boolean isMaterial(ProvisionChangedEvent event, Plan plan) {
    // Stocking up never invalidates a plan.
    if (event instanceof ItemAddedFromGroceryEvent) {
      return false;
    }

    Set<MealSlot> remaining = remainingPlannedSlots(plan);
    if (remaining.isEmpty()) {
      return false; // no degrees of freedom — nothing a re-opt could change
    }

    if (event instanceof ItemRanOutEvent ranOut) {
      String key = ranOut.ingredientMappingKey();
      if (key == null || key.isBlank()) {
        // Defensive: an item-ran-out with no key — over-trigger rather than miss a stock-out.
        return true;
      }
      return anyRemainingSlotUsesKey(remaining, key);
    }

    // ItemSpoiledEvent / ItemQuantityAdjustedEvent / SubstitutionAcceptedEvent /
    // GenericProvisionChangedEvent carry only inventory-row ids — no ingredient mapping key to
    // match against recipe ingredients. The
    // ticket's stated bias is to over-trigger (a missed stock-out is worse than an extra
    // suggestion the user can dismiss), so any plan with a regenerable slot is material.
    log.debug(
        "Provision event {} carries no ingredient mapping key; treating as material for plan {}"
            + " (conservative over-trigger)",
        event.getClass().getSimpleName(),
        plan.getId());
    return true;
  }

  private boolean anyRemainingSlotUsesKey(Set<MealSlot> remaining, String mappingKey) {
    String needle = mappingKey.toLowerCase(Locale.ROOT);
    Set<UUID> seenRecipeIds = new HashSet<>();
    for (MealSlot slot : remaining) {
      ScheduledRecipe sr = slot.getScheduledRecipe();
      if (sr == null || !seenRecipeIds.add(sr.getRecipeId())) {
        continue;
      }
      if (recipeUsesKey(sr.getRecipeId(), needle)) {
        return true;
      }
    }
    return false;
  }

  private boolean recipeUsesKey(UUID recipeId, String needleLowerCase) {
    Optional<RecipeDto> recipe;
    try {
      recipe = recipeQueryService.getById(recipeId);
    } catch (RuntimeException ex) {
      // A recipe lookup failure must not block the re-opt path — over-trigger (safer).
      log.warn(
          "Recipe lookup failed for recipeId={} during provision-materiality check ({}); treating"
              + " as material",
          recipeId,
          ex.toString());
      return true;
    }
    if (recipe.isEmpty() || recipe.get().currentVersionBody() == null) {
      return false;
    }
    for (IngredientDto ing : recipe.get().currentVersionBody().ingredients()) {
      String k = ing.ingredientMappingKey();
      if (k != null && k.toLowerCase(Locale.ROOT).equals(needleLowerCase)) {
        return true;
      }
    }
    return false;
  }

  private Set<MealSlot> remainingPlannedSlots(Plan plan) {
    Set<MealSlot> out = new HashSet<>();
    for (Day day : plan.getDays()) {
      for (MealSlot slot : day.getSlots()) {
        if (slot.getState() == SlotState.PLANNED) {
          out.add(slot);
        }
      }
    }
    return out;
  }
}
