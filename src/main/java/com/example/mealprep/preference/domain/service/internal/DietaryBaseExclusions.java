package com.example.mealprep.preference.domain.service.internal;

import java.util.Map;
import java.util.Set;

/**
 * Static lookup mapping a stored {@code dietaryIdentityBase} value (e.g. {@code vegan}) to the set
 * of ingredient keys it excludes. Reference data — small, stable, queried per filter call.
 *
 * <p>Kept as a class-level constant rather than DB-backed because (a) the set is tiny (~6 bases ×
 * ~20 keys), (b) it's part of the v1 product taxonomy, not user data, and (c) DB indirection here
 * would buy nothing on a hot read path. If the v2 product expands this materially we'll revisit.
 */
final class DietaryBaseExclusions {

  private DietaryBaseExclusions() {}

  private static final Set<String> ANIMAL_MEAT_KEYS =
      Set.of(
          "chicken",
          "beef",
          "pork",
          "lamb",
          "turkey",
          "duck",
          "veal",
          "venison",
          "bacon",
          "ham",
          "sausage",
          "salami",
          "prosciutto");

  private static final Set<String> ANIMAL_FISH_KEYS =
      Set.of(
          "fish",
          "tuna",
          "salmon",
          "cod",
          "haddock",
          "anchovy",
          "sardine",
          "mackerel",
          "trout",
          "shrimp",
          "prawn",
          "crab",
          "lobster",
          "scallop",
          "mussel",
          "oyster",
          "squid",
          "octopus");

  private static final Set<String> ANIMAL_PRODUCTS_KEYS =
      Set.of(
          "milk",
          "cheese",
          "butter",
          "cream",
          "yoghurt",
          "ghee",
          "whey",
          "casein",
          "lactose",
          "egg",
          "egg_white",
          "egg_yolk",
          "honey",
          "gelatin");

  /** Vegan = no animal flesh AND no animal-derived products. */
  private static final Set<String> VEGAN_EXCLUSIONS =
      union(ANIMAL_MEAT_KEYS, ANIMAL_FISH_KEYS, ANIMAL_PRODUCTS_KEYS);

  /** Vegetarian = no animal flesh, but eggs / dairy / honey allowed. */
  private static final Set<String> VEGETARIAN_EXCLUSIONS =
      union(ANIMAL_MEAT_KEYS, ANIMAL_FISH_KEYS);

  /** Pescatarian = no land-animal flesh; fish allowed. */
  private static final Set<String> PESCATARIAN_EXCLUSIONS = ANIMAL_MEAT_KEYS;

  private static final Map<String, Set<String>> BASE_TO_EXCLUSIONS =
      Map.of(
          "vegan", VEGAN_EXCLUSIONS,
          "vegetarian", VEGETARIAN_EXCLUSIONS,
          "pescatarian", PESCATARIAN_EXCLUSIONS);

  /** Returns the set of ingredient keys the given base diet excludes; empty for unknown bases. */
  static Set<String> exclusionsFor(String dietaryIdentityBase) {
    if (dietaryIdentityBase == null) {
      return Set.of();
    }
    return BASE_TO_EXCLUSIONS.getOrDefault(dietaryIdentityBase, Set.of());
  }

  @SafeVarargs
  private static Set<String> union(Set<String>... sets) {
    java.util.Set<String> out = new java.util.HashSet<>();
    for (Set<String> s : sets) {
      out.addAll(s);
    }
    return Set.copyOf(out);
  }
}
