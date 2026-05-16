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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Emits candidates dropping each optional ingredient. Per ticket 01c §Step 3 ingredient-remove
 * strategy.
 *
 * <p>Skips when no ingredient on the version carries {@code optional = true}.
 */
@Component
public class IngredientRemoveStrategy implements CandidateGenerationStrategy {

  @Override
  public String name() {
    return "ingredient-remove";
  }

  @Override
  public List<AdaptationCandidateDto> generate(AdaptationJob job, AdaptationContext context) {
    RecipeVersionDto version = context.currentVersion();
    if (version == null || version.ingredients() == null) {
      return List.of();
    }
    List<AdaptationCandidateDto> out = new ArrayList<>();
    int idx = 0;
    for (IngredientDto ing : version.ingredients()) {
      if (ing.optional()) {
        out.add(build(idx++, ing.ingredientMappingKey()));
      }
    }
    return out;
  }

  private AdaptationCandidateDto build(int index, String key) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "ingredient-remove");
    diff.put("key", key);
    AdaptationRollupDto rollup =
        new AdaptationRollupDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Map.of(),
            BigDecimal.ZERO,
            0,
            -1,
            BigDecimal.valueOf(0.5),
            Set.of(),
            List.of());
    return new AdaptationCandidateDto(
        index,
        AdaptationClassification.VERSION,
        diff,
        rollup,
        "remove " + key,
        "",
        BigDecimal.valueOf(0.7),
        BigDecimal.valueOf(0.55),
        List.of());
  }
}
