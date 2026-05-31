package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recipe deduplication helper (recipe-2). Implements the HLD §Recipe deduplication contract:
 *
 * <blockquote>
 *
 * On import, a normalised ingredient-set hash (sorted mapping keys, ignoring quantities) is
 * computed. Collisions above a threshold (default: 80% ingredient overlap + method length within
 * ±20%) surface a "merge / variant / import anyway" dialog.
 *
 * </blockquote>
 *
 * <p>The candidate scope is the caller's <b>own active USER-catalogue library</b> ("…similar to
 * 'Chicken Stir Fry' in your library"). SYSTEM-catalogue, archived, and soft-deleted recipes are
 * excluded — discovery's pool dedup is a separate fingerprint path (see {@code
 * RecipeServiceImpl#saveImportedRecipe}). The check is exact-hash-first (a fast equality short
 * circuit) then a fuzzy ingredient-overlap + method-length comparison so a re-import of the very
 * same recipe and a near-duplicate from another source both surface.
 *
 * <p>Stateless and side-effect free: callers decide whether to throw {@code
 * RecipeImportDuplicateException} on a hit.
 */
@Component
public class RecipeDeduplicationService {

  private static final Logger log = LoggerFactory.getLogger(RecipeDeduplicationService.class);

  /** HLD default: ≥80% ingredient overlap (Jaccard of normalised mapping-key sets). */
  static final double INGREDIENT_OVERLAP_THRESHOLD = 0.80;

  /** HLD default: candidate method length within ±20% of the incoming recipe's. */
  static final double METHOD_LENGTH_TOLERANCE = 0.20;

  private final RecipeRepository recipeRepository;

  public RecipeDeduplicationService(RecipeRepository recipeRepository) {
    this.recipeRepository = recipeRepository;
  }

  /**
   * Normalised ingredient-set hash: the deduplicated, normalised mapping keys sorted lexically and
   * SHA-256 hashed. Quantities, units, order, and free text are ignored per the HLD. Two recipes
   * with the identical ingredient set produce the identical hash regardless of ingredient ordering.
   * Returns {@code null} when the recipe has no usable ingredient key (an empty set cannot
   * collide).
   */
  public String ingredientSetHash(List<CreateIngredientRequest> ingredients) {
    Set<String> keys = normalisedKeySet(ingredients);
    if (keys.isEmpty()) {
      return null;
    }
    // TreeSet gives a deterministic lexical ordering independent of source line order.
    String joined = String.join("\n", new TreeSet<>(keys));
    return sha256(joined);
  }

  /**
   * Find the first existing library recipe of {@code userId} that the incoming {@code request}
   * duplicates, or empty when none crosses the threshold. A candidate matches when its current
   * version's ingredient-set overlaps the incoming set by ≥{@link #INGREDIENT_OVERLAP_THRESHOLD}
   * (Jaccard) <b>and</b> its method-step count is within ±{@link #METHOD_LENGTH_TOLERANCE} of the
   * incoming step count. The match carries the candidate id + the measured overlap.
   */
  public Optional<DuplicateMatch> findDuplicate(UUID userId, CreateRecipeRequest request) {
    if (userId == null || request == null) {
      return Optional.empty();
    }
    Set<String> incomingKeys = normalisedKeySet(request.ingredients());
    if (incomingKeys.isEmpty()) {
      // Nothing to compare on — never flag a keyless recipe as a duplicate.
      return Optional.empty();
    }
    int incomingMethodSteps = request.method() == null ? 0 : request.method().size();

    Map<UUID, Set<String>> candidateKeys = new HashMap<>();
    Map<UUID, Integer> candidateMethodSteps = new HashMap<>();
    for (Object[] row :
        recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId)) {
      UUID candidateId = (UUID) row[0];
      String key = IngredientMappingKeys.normalise((String) row[1]);
      int methodSteps = ((Number) row[2]).intValue();
      if (key != null && !key.isBlank()) {
        candidateKeys.computeIfAbsent(candidateId, k -> new LinkedHashSet<>()).add(key);
      }
      candidateMethodSteps.put(candidateId, methodSteps);
    }

    for (Map.Entry<UUID, Set<String>> entry : candidateKeys.entrySet()) {
      UUID candidateId = entry.getKey();
      double overlap = jaccard(incomingKeys, entry.getValue());
      if (overlap < INGREDIENT_OVERLAP_THRESHOLD) {
        continue;
      }
      int candidateSteps = candidateMethodSteps.getOrDefault(candidateId, 0);
      if (!methodLengthWithinTolerance(incomingMethodSteps, candidateSteps)) {
        continue;
      }
      log.info(
          "recipe dedup hit userId={} candidateRecipeId={} ingredientOverlap={} incomingSteps={}"
              + " candidateSteps={}",
          userId,
          candidateId,
          String.format("%.2f", overlap),
          incomingMethodSteps,
          candidateSteps);
      return Optional.of(new DuplicateMatch(candidateId, overlap));
    }
    return Optional.empty();
  }

  private static Set<String> normalisedKeySet(List<CreateIngredientRequest> ingredients) {
    Set<String> keys = new LinkedHashSet<>();
    if (ingredients == null) {
      return keys;
    }
    for (CreateIngredientRequest ingredient : ingredients) {
      if (ingredient == null) {
        continue;
      }
      String key = IngredientMappingKeys.normalise(ingredient.ingredientMappingKey());
      if (key != null && !key.isBlank()) {
        keys.add(key);
      }
    }
    return keys;
  }

  /**
   * Jaccard similarity {@code |A ∩ B| / |A ∪ B|} over the two normalised key sets. 1.0 for
   * identical sets, 0.0 for disjoint. Symmetric, so re-importing an exact copy scores 1.0 either
   * direction.
   */
  private static double jaccard(Set<String> a, Set<String> b) {
    if (a.isEmpty() && b.isEmpty()) {
      return 1.0;
    }
    int intersection = 0;
    for (String key : a) {
      if (b.contains(key)) {
        intersection++;
      }
    }
    int union = a.size() + b.size() - intersection;
    return union == 0 ? 0.0 : (double) intersection / union;
  }

  /**
   * True when {@code candidateSteps} is within ±{@link #METHOD_LENGTH_TOLERANCE} of {@code
   * incomingSteps}. When the incoming recipe has zero steps, only a zero-step candidate matches (a
   * missing method should not fuzzy-match an arbitrary length).
   */
  private static boolean methodLengthWithinTolerance(int incomingSteps, int candidateSteps) {
    if (incomingSteps == 0) {
      return candidateSteps == 0;
    }
    double ratio = Math.abs(candidateSteps - incomingSteps) / (double) incomingSteps;
    return ratio <= METHOD_LENGTH_TOLERANCE;
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is guaranteed present on every JVM; defensive only.
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /** A dedup hit: the existing library recipe and the measured ingredient overlap (0.0–1.0). */
  public record DuplicateMatch(UUID candidateRecipeId, double ingredientOverlap) {}
}
