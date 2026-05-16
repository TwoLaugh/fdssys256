package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.AdaptationCandidateDto;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Stage-B scorer: takes the hard-filter-survivors from Stage A, sorts by {@code tasteAlignmentScore
 * DESC, |macroDeltaKcal| ASC}, and takes the top-{@code N} per {@link
 * AdaptationConfig#candidateTopN()}. Indices are renumbered after the cut so downstream Stage-C
 * indexing stays 0-based.
 *
 * <p>Per ticket 01c §Stage B — v1 score uses the candidate's {@code rollup.tasteAlignmentScore}
 * directly (already computed by the strategies); macro delta breaks ties.
 */
@Component
public class ScoringEngine {

  private final AdaptationConfig config;

  public ScoringEngine(AdaptationConfig config) {
    this.config = config;
  }

  /**
   * Score and select top-N candidates; preserves original {@link AdaptationCandidateDto#rollup()}.
   */
  public List<AdaptationCandidateDto> selectTopN(List<AdaptationCandidateDto> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    List<AdaptationCandidateDto> sorted = new ArrayList<>(candidates);
    sorted.sort(
        Comparator.<AdaptationCandidateDto, BigDecimal>comparing(
                c -> c.rollup().tasteAlignmentScore(), Comparator.reverseOrder())
            .thenComparing(c -> absKcal(c)));
    int limit = Math.min(config.candidateTopN(), sorted.size());
    List<AdaptationCandidateDto> top = new ArrayList<>(limit);
    for (int i = 0; i < limit; i++) {
      AdaptationCandidateDto c = sorted.get(i);
      top.add(
          new AdaptationCandidateDto(
              i,
              c.proposedClassification(),
              c.proposedDiff(),
              c.rollup(),
              c.culinaryNotes(),
              c.nutritionalNotes(),
              c.characterPreservationScore(),
              c.estimatedConfidence(),
              c.plannerHints()));
    }
    return top;
  }

  /**
   * Returns true when the top candidate's score is more than {@code autoSkipTopRatio} times the
   * runner-up's score. Single-candidate input also returns true (no LLM tie-break needed). Per
   * ticket 01c §Stage C auto-skip rule.
   */
  public boolean shouldAutoSkipStageC(List<AdaptationCandidateDto> topN) {
    if (topN == null || topN.isEmpty()) {
      return false;
    }
    if (topN.size() == 1) {
      return true;
    }
    BigDecimal top = topN.get(0).rollup().tasteAlignmentScore();
    BigDecimal runner = topN.get(1).rollup().tasteAlignmentScore();
    if (runner.signum() <= 0) {
      // Runner-up is zero / negative — top wins trivially.
      return true;
    }
    BigDecimal ratio = top.divide(runner, java.math.MathContext.DECIMAL64);
    return ratio.compareTo(config.autoSkipTopRatio()) > 0;
  }

  private BigDecimal absKcal(AdaptationCandidateDto c) {
    BigDecimal kcal = c.rollup().macroDeltaKcal();
    return kcal == null ? BigDecimal.ZERO : kcal.abs();
  }
}
