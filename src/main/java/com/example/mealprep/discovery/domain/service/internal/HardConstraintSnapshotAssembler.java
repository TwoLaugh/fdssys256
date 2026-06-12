package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.preference.api.dto.FilterContext;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Server-side hard-constraint exclusion snapshot for discovery jobs (ticket {@code
 * discovery-server-side-exclusions}, P1 SAFETY).
 *
 * <p>{@code DiscoveryConstraints.mustExcludeIngredientMappingKeys} used to be populated entirely by
 * the caller — for {@code USER_INITIATED} jobs, the frontend. A buggy, stale, or malicious client
 * sending an empty list let allergy-violating recipes into the SYSTEM catalogue. This assembler
 * closes that hole at enqueue time: the SERVER derives the caller's hard-constraint exclusion set
 * via the preference module's published read seam ({@link
 * HardConstraintFilterService#exclusionKeySnapshot} — allergies expanded through {@code
 * preference_allergen_derivatives}, intolerances, medical diets, dietary base exclusions; the same
 * index the planner's filter matches against) and UNIONS it with whatever the client sent. Client
 * keys are additive only — they can narrow results ("also exclude mushrooms this time"), never
 * widen them.
 *
 * <p>Both sides are normalised via {@link IngredientMappingKeys#normalise} (core-03) before the
 * union so "Chicken Breast" and "chicken breast" merge. The effective set is frozen into the
 * persisted constraints snapshot — constraint edits mid-job still do not retroactively alter the
 * running job's filter.
 *
 * <p>{@link FilterContext#ANY} is used deliberately: discovery has no meal-slot day context, so
 * only universally-applicable dietary-identity exceptions widen the base diet — the conservative
 * default the runner's own live hard-constraint check already uses.
 *
 * <p><b>Fail-closed:</b> a preference-read failure propagates and the enqueue fails. Proceeding
 * with client-only keys would silently re-open the trust hole; {@code exclusionKeySnapshot} never
 * throws on missing data (no-constraints users get an empty set), so only genuine infrastructure
 * failures abort the start.
 */
@Component
public class HardConstraintSnapshotAssembler {

  private static final Logger log = LoggerFactory.getLogger(HardConstraintSnapshotAssembler.class);

  private final HardConstraintFilterService hardConstraintFilter;

  public HardConstraintSnapshotAssembler(HardConstraintFilterService hardConstraintFilter) {
    this.hardConstraintFilter = hardConstraintFilter;
  }

  /**
   * Returns a copy of {@code client} whose {@code mustExcludeIngredientMappingKeys} is the
   * normalised, deduplicated, deterministically-sorted union of the caller's server-derived
   * hard-constraint snapshot and the client-supplied keys. All other fields pass through unchanged.
   * Logs at INFO when the server snapshot adds keys the client omitted — that is the trust hole
   * closing, and it should be observable.
   */
  public DiscoveryConstraints withServerExclusions(UUID userId, DiscoveryConstraints client) {
    if (client == null) {
      // Defensive: REST enforces @NotNull and in-process callers always pass constraints; a null
      // document would fail deserialisation in the runner and the job finalises FAILED (safe).
      return null;
    }
    Set<String> clientKeys = normalised(client.mustExcludeIngredientMappingKeys());
    Set<String> serverKeys =
        normalised(hardConstraintFilter.exclusionKeySnapshot(userId, FilterContext.ANY));

    Set<String> effective = new TreeSet<>(clientKeys);
    effective.addAll(serverKeys);

    Set<String> serverAdded = new TreeSet<>(serverKeys);
    serverAdded.removeAll(clientKeys);
    if (!serverAdded.isEmpty()) {
      log.info(
          "discovery enqueue for user {}: server hard-constraint snapshot added {} exclusion"
              + " key(s) the client omitted: {}",
          userId,
          serverAdded.size(),
          serverAdded);
    }

    // Preserve the client's null-vs-empty shape when there is nothing to add.
    List<String> effectiveList =
        effective.isEmpty() ? client.mustExcludeIngredientMappingKeys() : List.copyOf(effective);

    return new DiscoveryConstraints(
        client.schemaVersion(),
        client.requiredCuisines(),
        client.requiredMealTypes(),
        client.maxTotalTimeMins(),
        effectiveList,
        client.dietaryFlags(),
        client.preferenceHints(),
        client.maxRecipesPerSource());
  }

  /** Normalise (core-03), drop null/blank, deduplicate. */
  private static Set<String> normalised(Collection<String> keys) {
    Set<String> out = new TreeSet<>();
    if (keys == null) {
      return out;
    }
    for (String key : keys) {
      String normalisedKey = IngredientMappingKeys.normalise(key);
      if (normalisedKey != null && !normalisedKey.isEmpty()) {
        out.add(normalisedKey);
      }
    }
    return out;
  }
}
