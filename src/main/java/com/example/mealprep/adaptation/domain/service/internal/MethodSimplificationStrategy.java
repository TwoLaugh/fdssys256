package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.recipe.api.dto.MethodStepDto;
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
 * Emits candidates dropping the longest method step OR collapsing two consecutive prep steps. Per
 * ticket 01c §Step 3 method-simplification strategy.
 */
@Component
public class MethodSimplificationStrategy implements CandidateGenerationStrategy {

  @Override
  public String name() {
    return "method-simplification";
  }

  @Override
  public List<AdaptationCandidateDto> generate(AdaptationJob job, AdaptationContext context) {
    RecipeVersionDto version = context.currentVersion();
    if (version == null || version.methodSteps() == null || version.methodSteps().size() < 2) {
      return List.of();
    }
    List<AdaptationCandidateDto> out = new ArrayList<>();
    // drop longest step
    int longestIdx = 0;
    int longestLen = -1;
    for (int i = 0; i < version.methodSteps().size(); i++) {
      MethodStepDto step = version.methodSteps().get(i);
      int len = step.instruction() == null ? 0 : step.instruction().length();
      if (len > longestLen) {
        longestLen = len;
        longestIdx = i;
      }
    }
    out.add(buildDrop(0, longestIdx));
    // collapse first two consecutive steps
    out.add(buildCollapse(1, 0, 1));
    return out;
  }

  private AdaptationCandidateDto buildDrop(int index, int stepIdx) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "method-drop");
    diff.put("stepIndex", stepIdx);
    return wrap(index, diff, "drop method step " + stepIdx);
  }

  private AdaptationCandidateDto buildCollapse(int index, int a, int b) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "method-collapse");
    diff.put("from", a);
    diff.put("to", b);
    return wrap(index, diff, "collapse steps " + a + "+" + b);
  }

  private AdaptationCandidateDto wrap(int index, ObjectNode diff, String note) {
    AdaptationRollupDto rollup =
        new AdaptationRollupDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Map.of(),
            BigDecimal.ZERO,
            -5,
            0,
            BigDecimal.valueOf(0.55),
            Set.of(),
            List.of());
    return new AdaptationCandidateDto(
        index,
        AdaptationClassification.VERSION,
        diff,
        rollup,
        note,
        "",
        BigDecimal.valueOf(0.75),
        BigDecimal.valueOf(0.6),
        List.of());
  }
}
