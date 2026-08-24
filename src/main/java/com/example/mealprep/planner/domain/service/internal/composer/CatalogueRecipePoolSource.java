package com.example.mealprep.planner.domain.service.internal.composer;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.preference.PreferenceModule;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * <p><b>Candidate SELECTION (not just a bigger read).</b> Candidates are fetched <i>per meal
 * kind</i> and ranked by <i>taste similarity</i>:
 *
 * <ul>
 *   <li><b>Per-kind</b> — the catalogue is heavily kind-skewed (the SYSTEM pool is dominated by
 *       lunch/dinner; breakfast and especially snack are rare). A single flat {@code created_at}
 *       read returns the oldest rows, which are overwhelmingly lunch/dinner, so snack/breakfast
 *       slots starve and the beam wastes time. Reading {@link #CANDIDATES_PER_KIND} candidates
 *       <i>for each distinct slot kind in the run</i> guarantees every kind has real candidates.
 *   <li><b>Taste-ranked</b> — within each kind, candidates come back ordered by ascending cosine
 *       distance to the household member's taste vector ({@code embedding <=> tasteVector}), so the
 *       member's most-liked recipes are the ones the beam scores first. The vector is read from the
 *       preference module as an opaque pgvector literal ({@link
 *       com.example.mealprep.preference.domain.service.TasteSimilarityQueryService#getTasteVectorLiteral});
 *       a member with no {@code EMBEDDED} vector (cold start) transparently falls back to {@code
 *       created_at} ordering, still per-kind.
 * </ul>
 *
 * <p>This replaces the original flat {@code findPlannableCandidates(userId, limit)} read, whose own
 * javadoc flagged that "the real scale lever is a smarter candidate SELECTION (rank/sample by
 * nutrition-fit or embedding similarity), not a bigger number here."
 *
 * <p><b>Scope = caller's USER recipes &cup; SYSTEM recipes.</b> The recipe read scopes USER rows to
 * the supplied user-id and always includes the global SYSTEM catalogue. SYSTEM rows are visible to
 * everyone; USER rows are per-user — this per-user scoping is what keeps the E2E soak suite
 * isolated (one user's private recipes never leak into another user's plan), while the shared
 * SYSTEM catalogue gives every household a baseline pool.
 *
 * <p>This is an <b>unconditional</b> {@code @Component}, so it is the only {@link RecipePoolSource}
 * in a normal context. The fallback {@link NoOpRecipePoolSource} carries
 * {@code @ConditionalOnMissingBean(RecipePoolSource.class)} so it only registers when nothing else
 * supplies the seam (e.g. a slice test that excludes this bean).
 *
 * <p>Kind / time-budget filtering is STILL re-applied downstream — the planner's {@code
 * HardFilterRunner} enforces the per-slot kind + time-budget + equipment + hard-constraint filters,
 * so a recipe tagged for several kinds only lands in a matching slot. The per-kind read here is a
 * SELECTION narrowing (which candidates to surface), not the authoritative slot filter.
 */
@Component
class CatalogueRecipePoolSource implements RecipePoolSource {

  private static final Logger log = LoggerFactory.getLogger(CatalogueRecipePoolSource.class);

  /**
   * Per-kind candidate budget. We read this many taste-ranked candidates per distinct slot kind so
   * even a kind-skewed catalogue surfaces enough variety per kind. Bounded overall by {@link
   * #MAX_POOL_SIZE}.
   *
   * <p>SCALE NOTE: beam latency rises with the candidate count (cap≈2000 timed out a 120s client;
   * ≈150/kind ran ~90s). With taste-ranked selection the first candidates are already the best, so
   * this can be tuned DOWN later for speed without losing plan quality — the opposite of the flat
   * {@code created_at} read, where a bigger number only re-fed the same oldest prefix.
   */
  private static final int CANDIDATES_PER_KIND = 150;

  /** Hard ceiling on the fetched pool size (across all kinds + members). */
  private static final int MAX_POOL_SIZE = 750;

  /**
   * How hard a recipe's EXCESS fat (fraction of its calories above the eater's target fat-fraction)
   * demotes it within a kind's taste-ranked block — in candidate positions per unit of excess.
   * Bounded + soft (taste still dominates): a dish at +25 percentage-points of fat moves ~{@code
   * 0.25 × this} places. This is the "bring fat DOWN vs a fatty pool" lever — portioning alone
   * can't make a fatty pool lean, but biasing selection toward lower-fat-ratio dishes can.
   */
  private static final double FAT_RERANK_POSITIONS = 150.0;

  private final HouseholdQueryService householdQueryService;
  private final RecipeQueryService recipeQueryService;
  private final PreferenceModule preferenceModule;
  private final NutritionQueryService nutritionQueryService;

  CatalogueRecipePoolSource(
      HouseholdQueryService householdQueryService,
      RecipeQueryService recipeQueryService,
      PreferenceModule preferenceModule,
      NutritionQueryService nutritionQueryService) {
    this.nutritionQueryService = nutritionQueryService;
    this.householdQueryService = householdQueryService;
    this.recipeQueryService = recipeQueryService;
    this.preferenceModule = preferenceModule;
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

    List<String> mealTypes = distinctMealTypes(skeletons);

    // No resolvable standard kind (e.g. a CUSTOM-only run, or no skeletons yet): fall back to the
    // legacy flat read so the pool is never empty for an exotic slot layout.
    if (mealTypes.isEmpty()) {
      return fetchFlatFallback(memberUserIds, traceId);
    }

    // Union over members × kinds, de-duplicated by recipe id (SYSTEM rows appear for every member
    // and in every kind they're tagged with; a recipe must enter the pool once). Insertion order
    // keeps each kind's taste-ranked block contiguous so the per-slot cap downstream takes the
    // top-ranked candidates of the matching kind.
    // Macro-aware re-rank target: the primary eater's fat-fraction-of-calories. When set, each
    // kind's taste-ranked block is softly re-ordered to demote dishes whose fat ratio runs above it
    // — so the beam gets leaner raw material from a fatty pool, without abandoning taste.
    double targetFatFraction = resolveTargetFatFraction(memberUserIds);

    Map<UUID, RecipeDto> byId = new LinkedHashMap<>();
    int tasteAwareMembers = 0;
    for (UUID userId : memberUserIds) {
      Optional<String> tasteVector =
          preferenceModule.tasteSimilarity().getTasteVectorLiteral(userId);
      if (tasteVector.isPresent()) {
        tasteAwareMembers++;
      }
      for (String mealType : mealTypes) {
        if (byId.size() >= MAX_POOL_SIZE) {
          break;
        }
        List<RecipeDto> candidates =
            recipeQueryService.findPlannableCandidatesByKind(
                userId, mealType, CANDIDATES_PER_KIND, tasteVector.orElse(null));
        candidates = macroReRank(candidates, targetFatFraction);
        for (RecipeDto candidate : candidates) {
          if (candidate != null && candidate.id() != null) {
            byId.putIfAbsent(candidate.id(), candidate);
          }
          if (byId.size() >= MAX_POOL_SIZE) {
            break;
          }
        }
      }
    }

    List<RecipeDto> pool = new ArrayList<>(byId.values());
    log.debug(
        "Recipe pool: {} candidate(s) for household {} across {} member(s), {} kind(s) {}; "
            + "{}/{} member(s) taste-ranked (trace {})",
        pool.size(),
        householdId,
        memberUserIds.size(),
        mealTypes.size(),
        mealTypes,
        tasteAwareMembers,
        memberUserIds.size(),
        traceId);
    return pool;
  }

  /**
   * The primary eater's target fat-fraction (fat kcal ÷ calorie target), or a negative sentinel
   * when no usable target exists (→ macro re-rank is skipped, pure taste order). First member with
   * both a calorie target and a fat target wins.
   */
  private double resolveTargetFatFraction(List<UUID> memberUserIds) {
    for (UUID userId : memberUserIds) {
      Optional<TargetsDto> t = nutritionQueryService.getTargets(userId);
      if (t.isEmpty()
          || t.get().calories() == null
          || t.get().calories().dailyTarget() <= 0
          || t.get().fat() == null
          || t.get().fat().targetG() == null) {
        continue;
      }
      double fatKcal = t.get().fat().targetG().doubleValue() * 9.0;
      return fatKcal / t.get().calories().dailyTarget();
    }
    return -1.0;
  }

  /**
   * Soft, stable re-rank of a kind's taste-ranked block: keep the taste order but add a penalty,
   * measured in candidate positions, for each recipe's EXCESS fat-fraction over {@code
   * targetFatFraction}. No-op when there is no target or the input is tiny.
   */
  private static List<RecipeDto> macroReRank(
      List<RecipeDto> tasteRanked, double targetFatFraction) {
    if (targetFatFraction <= 0 || tasteRanked.size() < 3) {
      return tasteRanked;
    }
    Map<UUID, Double> rank = new HashMap<>();
    for (int i = 0; i < tasteRanked.size(); i++) {
      RecipeDto r = tasteRanked.get(i);
      rank.put(r.id(), i + FAT_RERANK_POSITIONS * fatExcessFraction(r, targetFatFraction));
    }
    List<RecipeDto> out = new ArrayList<>(tasteRanked);
    out.sort(Comparator.comparingDouble(r -> rank.getOrDefault(r.id(), 0.0)));
    return out;
  }

  /** {@code max(0, recipeFatFraction − target)}; 0 when the recipe carries no usable nutrition. */
  private static double fatExcessFraction(RecipeDto recipe, double targetFatFraction) {
    if (recipe == null) {
      return 0.0;
    }
    RecipeVersionDto v = recipe.currentVersionBody();
    NutritionPerServingDto n = v == null ? null : v.nutritionPerServing();
    if (n == null || n.calories() <= 0 || n.fatG() == null) {
      return 0.0;
    }
    double recipeFatFraction = n.fatG().doubleValue() * 9.0 / n.calories();
    return Math.max(0.0, recipeFatFraction - targetFatFraction);
  }

  /**
   * Legacy flat read — union of each member's plannable catalogue ({@code created_at} order),
   * de-duplicated by recipe id. Only used when no standard meal kind resolves from the skeletons
   * (CUSTOM-only / empty run), where per-kind selection has nothing to key on.
   */
  private List<RecipeDto> fetchFlatFallback(List<UUID> memberUserIds, UUID traceId) {
    Map<UUID, RecipeDto> byId = new LinkedHashMap<>();
    for (UUID userId : memberUserIds) {
      for (RecipeDto candidate :
          recipeQueryService.findPlannableCandidates(userId, MAX_POOL_SIZE)) {
        if (candidate != null && candidate.id() != null) {
          byId.putIfAbsent(candidate.id(), candidate);
        }
      }
    }
    List<RecipeDto> pool = new ArrayList<>(byId.values());
    log.debug(
        "Recipe pool (flat fallback, no standard kind): {} candidate(s) across {} member(s) "
            + "(trace {})",
        pool.size(),
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
   * Distinct lower-case meal types to read candidates for, derived from the run's slot kinds. Only
   * the four standard kinds ({@code BREAKFAST/LUNCH/DINNER/SNACK}) map to a {@code
   * recipe_metadata.meal_types} tag; {@code CUSTOM} (and any unmapped kind) is skipped here and
   * handled by {@link #fetchFlatFallback}. Order follows first-seen in the skeleton list.
   */
  private List<String> distinctMealTypes(List<MealSlotSkeleton> skeletons) {
    if (skeletons == null) {
      return List.of();
    }
    Set<String> mealTypes = new LinkedHashSet<>();
    for (MealSlotSkeleton skel : skeletons) {
      if (skel == null || skel.kind() == null) {
        continue;
      }
      String mealType = toMealType(skel.kind());
      if (mealType != null) {
        mealTypes.add(mealType);
      }
    }
    return new ArrayList<>(mealTypes);
  }

  /**
   * Map a {@link SlotKind} to the lower-case {@code meal_types} tag the recipe catalogue stores, or
   * {@code null} for kinds that carry no catalogue tag ({@code CUSTOM}).
   */
  private static String toMealType(SlotKind kind) {
    return switch (kind) {
      case BREAKFAST, LUNCH, DINNER, SNACK -> kind.name().toLowerCase(Locale.ROOT);
      case CUSTOM -> null;
    };
  }
}
