package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.household.api.dto.LifestyleConfigDocument;
import com.example.mealprep.household.api.dto.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealSchedule;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.MealTiming;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyMode;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.NoveltyTolerance;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless projection of the preference module's rich domain documents down to the
 * lightweight, NON-VECTOR household soft-preference shapes consumed by {@code
 * SoftPreferenceMerger}.
 *
 * <p><b>Scope boundary (household/preference-01g):</b> this projection reads ONLY the non-vector
 * soft-preference signal — ingredient/cuisine like-scores, the avoid list, and the lifestyle
 * window/novelty/batch flags. It deliberately takes the {@code TasteProfileDocument} (the JSONB
 * payload) rather than the {@code TasteProfile} entity, so it has no syntactic access to the
 * entity's {@code tasteVector*} status fields — the type system makes the vector unreadable here.
 * The taste vector / embedding belongs to the deferred pgvector vertical and is intentionally never
 * surfaced.
 *
 * <p>All mappings are deterministic so a user with a built taste vector and one with a {@code
 * PENDING}/unbuilt vector produce byte-identical bundles (the merge does not wait on embeddings).
 */
final class SoftPreferenceProjection {

  /** Like-score for a favourited ingredient / cuisine. */
  private static final BigDecimal LIKE_FAVOURITE = BigDecimal.ONE;

  /** Like-score for an "enjoyed" (positive but not favourite) cuisine. */
  private static final BigDecimal LIKE_ENJOYS = new BigDecimal("0.5");

  /** Like-score for a "less preferred" cuisine. */
  private static final BigDecimal LIKE_LESS_PREFERRED = new BigDecimal("-0.5");

  /** Like-score for a disliked ingredient. */
  private static final BigDecimal LIKE_DISLIKED = BigDecimal.ONE.negate();

  private SoftPreferenceProjection() {}

  /**
   * Project a preference taste-profile document to the household {@link TasteProfileDocument}.
   * Returns {@code null} for a {@code null} input so the bundle carries a null-fielded taste
   * profile for a user with no profile (the merger handles nulls cleanly).
   */
  static TasteProfileDocument toHouseholdTasteProfile(
      com.example.mealprep.preference.domain.document.TasteProfileDocument source) {
    if (source == null) {
      return null;
    }
    Map<String, BigDecimal> ingredientLikes = new LinkedHashMap<>();
    List<String> avoidList = new ArrayList<>();
    var ingredientPrefs = source.ingredientPreferences();
    if (ingredientPrefs != null) {
      // Favourites first (positive), then disliked (negative) — disliked wins on the rare overlap
      // so
      // an avoided ingredient never reads as liked. Disliked items also seed the avoid list (these
      // are ingredientMappingKeys the user avoids — NOT allergens, which are hard constraints).
      if (ingredientPrefs.favourites() != null) {
        for (var fav : ingredientPrefs.favourites()) {
          if (fav != null && fav.item() != null) {
            ingredientLikes.put(fav.item(), LIKE_FAVOURITE);
          }
        }
      }
      if (ingredientPrefs.disliked() != null) {
        for (var dis : ingredientPrefs.disliked()) {
          if (dis != null && dis.item() != null) {
            ingredientLikes.put(dis.item(), LIKE_DISLIKED);
            if (!avoidList.contains(dis.item())) {
              avoidList.add(dis.item());
            }
          }
        }
      }
    }

    Map<String, BigDecimal> cuisineLikes = new LinkedHashMap<>();
    var cuisinePrefs = source.cuisinePreferences();
    if (cuisinePrefs != null) {
      putAll(cuisineLikes, cuisinePrefs.favourites(), LIKE_FAVOURITE);
      putAll(cuisineLikes, cuisinePrefs.enjoys(), LIKE_ENJOYS);
      putAll(cuisineLikes, cuisinePrefs.lessPreferred(), LIKE_LESS_PREFERRED);
    }

    return new TasteProfileDocument(ingredientLikes, cuisineLikes, avoidList);
  }

  /**
   * Project a preference lifestyle-config document to the household {@link
   * LifestyleConfigDocument}. Returns {@code null} for a {@code null} input so the bundle carries a
   * null-fielded lifestyle config for a user with no config.
   *
   * <ul>
   *   <li><b>window start/end</b> — the earliest start and latest end across the per-slot {@code
   *       "HH:mm-HH:mm"} ranges in {@code mealTiming.preferredSchedule.times}. Malformed / blank
   *       range strings are skipped.
   *   <li><b>noveltyTolerancePercent</b> — the maximum {@code newPerWeek} found across novelty
   *       slots, clamped to {@code [0, 100]}; {@code null} when no slot carries a {@code
   *       newPerWeek}.
   *   <li><b>batchCookingPreferred</b> — {@code true} iff the user has at least one configured
   *       batch prep day.
   * </ul>
   */
  static LifestyleConfigDocument toHouseholdLifestyleConfig(
      com.example.mealprep.preference.domain.document.LifestyleConfigDocument source) {
    if (source == null) {
      return null;
    }
    String windowStart = null;
    String windowEnd = null;
    MealTiming mealTiming = source.mealTiming();
    if (mealTiming != null) {
      MealSchedule schedule = mealTiming.preferredSchedule();
      if (schedule != null && schedule.times() != null) {
        for (String range : schedule.times().values()) {
          String[] ends = splitRange(range);
          if (ends == null) {
            continue;
          }
          if (windowStart == null || ends[0].compareTo(windowStart) < 0) {
            windowStart = ends[0];
          }
          if (windowEnd == null || ends[1].compareTo(windowEnd) > 0) {
            windowEnd = ends[1];
          }
        }
      }
    }

    Integer noveltyPercent = null;
    NoveltyTolerance novelty = source.noveltyTolerance();
    if (novelty != null && novelty.bySlot() != null) {
      for (NoveltyMode mode : novelty.bySlot().values()) {
        if (mode != null && mode.newPerWeek() != null) {
          int clamped = Math.max(0, Math.min(100, mode.newPerWeek()));
          if (noveltyPercent == null || clamped > noveltyPercent) {
            noveltyPercent = clamped;
          }
        }
      }
    }

    boolean batchPreferred =
        source.batchCooking() != null
            && source.batchCooking().prepDays() != null
            && !source.batchCooking().prepDays().isEmpty();

    return new LifestyleConfigDocument(windowStart, windowEnd, noveltyPercent, batchPreferred);
  }

  private static void putAll(Map<String, BigDecimal> target, List<String> keys, BigDecimal score) {
    if (keys == null) {
      return;
    }
    for (String key : keys) {
      if (key != null) {
        target.put(key, score);
      }
    }
  }

  /**
   * Split an {@code "HH:mm-HH:mm"} range into {@code [start, end]}, or {@code null} if the value is
   * blank or not a single-hyphen two-part range. Lexicographic ordering of zero-padded {@code
   * HH:mm} strings equals chronological ordering, so callers compare the parts as strings.
   */
  private static String[] splitRange(String range) {
    if (range == null || range.isBlank()) {
      return null;
    }
    String[] parts = range.split("-");
    if (parts.length != 2) {
      return null;
    }
    String start = parts[0].trim();
    String end = parts[1].trim();
    if (start.isEmpty() || end.isEmpty()) {
      return null;
    }
    return new String[] {start, end};
  }
}
