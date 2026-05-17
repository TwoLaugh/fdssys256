package com.example.mealprep.planner.domain.service.internal.beamsearch;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.provisions.api.dto.EquipmentDto;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the per-slot recipe pool for one beam-search run by applying the four hard filters (kind,
 * time-budget, equipment, hard-constraints) per LLD §{@code BeamSearchEngine} lines 654-658. Pure
 * function — no DB writes; the only collaborator is the read-only {@link
 * HardConstraintFilterService} from {@code preference-01b}.
 *
 * <p>Empty pools are returned as empty lists keyed by the {@code slotId} (insertion-ordered via
 * {@code LinkedHashMap}) — empty-pool detection is the composer's {@code
 * ConstraintFeasibilityCheck} job in 01j; 01d does not raise an exception.
 *
 * <p>Per-slot list is capped at {@code maxPoolPerSlot} after sorting by recipe id ascending — the
 * cap is bounded-search hygiene; the beam-search's scoring step selects the actual best.
 */
@Component
class HardFilterRunner {

  private final HardConstraintFilterService hardConstraintFilterService;
  private final PlannerProperties properties;

  HardFilterRunner(
      HardConstraintFilterService hardConstraintFilterService, PlannerProperties properties) {
    this.hardConstraintFilterService = hardConstraintFilterService;
    this.properties = properties;
  }

  Map<UUID, List<RecipeDto>> filterPool(PlanCompositionContext ctx) {
    Set<String> availableEquipment =
        ctx.provisions() == null || ctx.provisions().equipment() == null
            ? Set.of()
            : ctx.provisions().equipment().stream()
                .filter(EquipmentDto::available)
                .map(EquipmentDto::name)
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

    Map<UUID, List<RecipeDto>> result = new LinkedHashMap<>();
    for (MealSlotSkeleton skel : ctx.slotSkeletons()) {
      List<RecipeDto> filtered =
          ctx.recipePool().recipes().stream()
              .filter(r -> matchesKind(r, skel.kind()))
              .filter(r -> withinTimeBudget(r, skel.timeBudgetMin()))
              .filter(r -> hasRequiredEquipment(r, availableEquipment))
              .filter(r -> passesHardConstraints(r, skel))
              .sorted(Comparator.comparing(RecipeDto::id))
              .limit(properties.maxPoolPerSlot())
              .toList();
      result.put(skel.slotId(), filtered);
    }
    return result;
  }

  private boolean matchesKind(RecipeDto recipe, SlotKind slotKind) {
    RecipeVersionDto version = recipe.currentVersionBody();
    if (version == null || version.metadata() == null) {
      return false;
    }
    List<String> mealTypes = version.metadata().mealTypes();
    if (mealTypes == null || mealTypes.isEmpty()) {
      return false;
    }
    String target = slotKind.name().toLowerCase(Locale.ROOT);
    return mealTypes.stream()
        .filter(Objects::nonNull)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .anyMatch(target::equals);
  }

  private boolean withinTimeBudget(RecipeDto recipe, int budgetMin) {
    RecipeVersionDto version = recipe.currentVersionBody();
    if (version == null || version.metadata() == null) {
      return false;
    }
    double cap = budgetMin * properties.maxTimeOvershootRatio().doubleValue();
    return version.metadata().totalTimeMins() <= Math.round(cap);
  }

  private boolean hasRequiredEquipment(RecipeDto recipe, Set<String> available) {
    RecipeVersionDto version = recipe.currentVersionBody();
    if (version == null || version.metadata() == null) {
      return false;
    }
    List<String> required = version.metadata().equipmentRequired();
    if (required == null || required.isEmpty()) {
      return true; // no equipment required = always available
    }
    return required.stream()
        .filter(Objects::nonNull)
        .map(s -> s.toLowerCase(Locale.ROOT))
        .allMatch(available::contains);
  }

  private boolean passesHardConstraints(RecipeDto recipe, MealSlotSkeleton skel) {
    RecipeVersionDto version = recipe.currentVersionBody();
    if (version == null) {
      return false;
    }
    List<String> keys =
        version.ingredients() == null
            ? List.of()
            : version.ingredients().stream()
                .map(IngredientDto::ingredientMappingKey)
                .filter(Objects::nonNull)
                .toList();
    if (skel.shared()) {
      // Shared slot: union check across all eaters via checkForHousehold (preference-01b's
      // contract takes the userId list directly so the planner does not need a household lookup).
      List<UUID> eaters = skel.eaters() == null ? List.of() : skel.eaters();
      if (eaters.isEmpty()) {
        return true;
      }
      FilterResult fr = hardConstraintFilterService.checkForHousehold(eaters, keys);
      return fr.passes();
    }
    // Per-person slot: every eater must individually pass. 01d divergence per ticket §
    // edge-cases for per-person multi-eater (rare; usually per-person == single eater).
    List<UUID> eaters = skel.eaters() == null ? List.of() : skel.eaters();
    if (eaters.isEmpty()) {
      return true;
    }
    for (UUID eater : eaters) {
      FilterResult fr = hardConstraintFilterService.check(eater, keys);
      if (!fr.passes()) {
        return false;
      }
    }
    return true;
  }
}
