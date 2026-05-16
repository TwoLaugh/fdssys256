package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Emits candidates substituting each top-3 ingredient with v1 hard-coded substitutes. Per ticket
 * 01c §Step 3 — v1 swap-knowledge source is a static map until 01e (real swap engine).
 *
 * <p>Examples per ticket: {@code beef -> chicken}, {@code wheat flour -> gluten-free flour}.
 */
@Component
public class IngredientSwapStrategy implements CandidateGenerationStrategy {

  /** v1 static swap table; key is canonical ingredient mapping key, value is substitute key. */
  private static final Map<String, List<String>> SWAPS;

  static {
    Map<String, List<String>> m = new HashMap<>();
    m.put("beef", List.of("chicken", "turkey", "tofu"));
    m.put("chicken", List.of("turkey", "tofu", "beef"));
    m.put("wheat_flour", List.of("gluten_free_flour", "almond_flour"));
    m.put("butter", List.of("olive_oil", "coconut_oil"));
    m.put("milk", List.of("almond_milk", "oat_milk"));
    m.put("salt", List.of("low_sodium_salt", "lemon_juice"));
    SWAPS = Collections.unmodifiableMap(m);
  }

  @Override
  public String name() {
    return "ingredient-swap";
  }

  @Override
  public List<AdaptationCandidateDto> generate(AdaptationJob job, AdaptationContext context) {
    RecipeVersionDto version = context.currentVersion();
    if (version == null || version.ingredients() == null || version.ingredients().isEmpty()) {
      return List.of();
    }
    List<AdaptationCandidateDto> out = new ArrayList<>();
    int index = 0;
    int maxTop = Math.min(3, version.ingredients().size());
    for (int i = 0; i < maxTop; i++) {
      IngredientDto ing = version.ingredients().get(i);
      String key = ing.ingredientMappingKey();
      List<String> subs = SWAPS.get(key);
      if (subs == null) {
        continue;
      }
      for (String sub : subs) {
        out.add(buildCandidate(index++, key, sub));
      }
    }
    return out;
  }

  private AdaptationCandidateDto buildCandidate(int index, String from, String to) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-swap");
    diff.put("from", from);
    diff.put("to", to);
    AdaptationRollupDto rollup =
        new AdaptationRollupDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Map.of(),
            BigDecimal.ZERO,
            0,
            0,
            BigDecimal.valueOf(0.8),
            Set.of(),
            List.of());
    return new AdaptationCandidateDto(
        index,
        AdaptationClassification.VERSION,
        diff,
        rollup,
        "swap " + from + " -> " + to,
        "",
        BigDecimal.valueOf(0.85),
        BigDecimal.valueOf(0.7),
        List.of());
  }
}
