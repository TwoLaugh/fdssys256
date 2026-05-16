package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.api.dto.AdaptationRollupDto;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Emits portion-adjust candidates at 0.75x, 0.875x, 1.25x, 1.5x the current serving. Per ticket 01c
 * §Step 3 portion-adjust strategy.
 */
@Component
public class PortionAdjustStrategy implements CandidateGenerationStrategy {

  private static final BigDecimal[] FACTORS = {
    BigDecimal.valueOf(0.75),
    BigDecimal.valueOf(0.875),
    BigDecimal.valueOf(1.25),
    BigDecimal.valueOf(1.5)
  };

  @Override
  public String name() {
    return "portion-adjust";
  }

  @Override
  public List<AdaptationCandidateDto> generate(AdaptationJob job, AdaptationContext context) {
    List<AdaptationCandidateDto> out = new ArrayList<>();
    for (int i = 0; i < FACTORS.length; i++) {
      out.add(build(i, FACTORS[i]));
    }
    return out;
  }

  private AdaptationCandidateDto build(int index, BigDecimal factor) {
    ObjectNode diff = JsonNodeFactory.instance.objectNode();
    diff.put("kind", "portion-adjust");
    diff.put("factor", factor);
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
            BigDecimal.valueOf(0.6),
            Set.of(),
            List.of());
    return new AdaptationCandidateDto(
        index,
        AdaptationClassification.VERSION,
        diff,
        rollup,
        "portion factor " + factor,
        "",
        BigDecimal.valueOf(0.9),
        BigDecimal.valueOf(0.6),
        List.of());
  }
}
