package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real {@link RecipePoolSource} backed by the recipe catalogue (planner Tier-1). Resolves the
 * planning household to its member user-ids (the same household read the rest of the composer
 * trusts — see {@link PlanCompositionContextBuilder}), then asks {@link RecipeQueryService} for
 * each member's plannable candidates. The returned pool is the union, de-duplicated by recipe id.
 *
 * <p><b>Scope = caller's USER recipes &cup; SYSTEM recipes.</b> The recipe read ({@link
 * RecipeQueryService#findPlannableCandidates}) scopes USER rows to the supplied user-id and always
 * includes the global SYSTEM catalogue. SYSTEM rows are visible to everyone; USER rows are per-user
 * — this per-user scoping is what keeps the E2E soak suite isolated (one user's private recipes
 * never leak into another user's plan), while the shared SYSTEM catalogue gives every household a
 * baseline pool.
 *
 * <p>This is an <b>unconditional</b> {@code @Component}, so it is the only {@link RecipePoolSource}
 * in a normal context. The fallback {@link NoOpRecipePoolSource} carries
 * {@code @ConditionalOnMissingBean(RecipePoolSource.class)} so it only registers when nothing else
 * supplies the seam (e.g. a slice test that excludes this bean). Preferring the conditional
 * fallback over {@code @Primary} sidesteps the documented multi-{@code @Primary} collision with
 * test stand-ins (see {@link PlanCompositionContextBuilder} javadoc).
 *
 * <p>Kind / time-budget filtering is NOT done here — the recipe read is deliberately narrow and the
 * planner's {@code HardFilterRunner} applies the per-slot kind + time-budget + equipment +
 * hard-constraint filters downstream. This source only narrows the candidate read to the slot kinds
 * actually present in {@code skeletons} (used to size the fetch, not to filter), per planner.md
 * §BeamSearchEngine Stage A.
 */
@Component
class CatalogueRecipePoolSource implements RecipePoolSource {

  private static final Logger log = LoggerFactory.getLogger(CatalogueRecipePoolSource.class);

  /**
   * Per-kind candidate budget. The pool feeds every slot before the planner's per-slot cap ({@code
   * mealprep.planner.max-pool-per-slot}); we read this many candidates per distinct slot kind so
   * even a kind-skewed catalogue surfaces enough variety. Bounded by {@link #MAX_POOL_SIZE} so a
   * large catalogue never floods the in-memory beam search.
   */
  private static final int CANDIDATES_PER_KIND = 50;

  /** Hard ceiling on the fetched pool size (bounded-read hygiene). */
  private static final int MAX_POOL_SIZE = 500;

  private final HouseholdQueryService householdQueryService;
  private final RecipeQueryService recipeQueryService;

  CatalogueRecipePoolSource(
      HouseholdQueryService householdQueryService, RecipeQueryService recipeQueryService) {
    this.householdQueryService = householdQueryService;
    this.recipeQueryService = recipeQueryService;
  }

  @Override
  public List<RecipeDto> fetchPool(
      UUID householdId, List<MealSlotSkeleton> skeletons, UUID traceId) {
    List<UUID> memberUserIds = resolveMemberUserIds(householdId);
    if (memberUserIds.isEmpty()) {
      log.warn(
          "Recipe pool: household {} has no resolvable members (trace {}); empty pool.",
          householdId,
          traceId);
      return List.of();
    }

    int limit = candidateLimit(skeletons);

    // Union over members, de-duplicated by recipe id (SYSTEM rows appear for every member; a
    // multi-member household must not schedule the same SYSTEM recipe twice in the pool).
    Map<UUID, RecipeDto> byId = new LinkedHashMap<>();
    for (UUID userId : memberUserIds) {
      for (RecipeDto candidate : recipeQueryService.findPlannableCandidates(userId, limit)) {
        if (candidate != null && candidate.id() != null) {
          byId.putIfAbsent(candidate.id(), candidate);
        }
      }
    }

    List<RecipeDto> pool = new ArrayList<>(byId.values());
    log.debug(
        "Recipe pool: {} candidate(s) for household {} across {} member(s) (trace {})",
        pool.size(),
        householdId,
        memberUserIds.size(),
        traceId);
    return pool;
  }

  /**
   * Resolve the household to the distinct user-ids whose USER catalogue should feed the pool. Reads
   * the household roster via {@link HouseholdQueryService#getById} (members eagerly populated, per
   * its contract). A missing household / empty roster yields an empty list (the planner degrades to
   * an empty pool + quality-warning plan, same as the no-op fallback).
   */
  private List<UUID> resolveMemberUserIds(UUID householdId) {
    if (householdId == null) {
      return List.of();
    }
    HouseholdDto household = householdQueryService.getById(householdId).orElse(null);
    if (household == null || household.members() == null) {
      return List.of();
    }
    Set<UUID> userIds = new LinkedHashSet<>();
    for (HouseholdMemberDto member : household.members()) {
      if (member != null && member.userId() != null) {
        userIds.add(member.userId());
      }
    }
    return new ArrayList<>(userIds);
  }

  /**
   * Size the per-member candidate read from the number of <i>distinct</i> slot kinds in the run.
   * More kinds ⇒ a wider read so each kind has candidates to survive the per-slot hard filters.
   * Bounded by {@link #MAX_POOL_SIZE}. An empty/absent skeleton list still reads at least one
   * kind's worth (so a pool can be inspected even before slots resolve).
   */
  private int candidateLimit(List<MealSlotSkeleton> skeletons) {
    Set<SlotKind> kinds = new LinkedHashSet<>();
    if (skeletons != null) {
      for (MealSlotSkeleton skel : skeletons) {
        if (skel != null && skel.kind() != null) {
          kinds.add(skel.kind());
        }
      }
    }
    int distinctKinds = Math.max(1, kinds.size());
    long limit = (long) distinctKinds * CANDIDATES_PER_KIND;
    return (int) Math.min(limit, MAX_POOL_SIZE);
  }
}
