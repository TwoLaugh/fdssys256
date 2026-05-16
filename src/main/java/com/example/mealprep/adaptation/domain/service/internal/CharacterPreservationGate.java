package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.exception.AdaptationCharacterBreakException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Character-preservation gate: rejects candidates whose {@code characterPreservationScore < 0.6}
 * UNLESS the LLM also classifies the change as {@code BRANCH} with a high-coherence proxy ({@code
 * confidence > 0.7}) — in which case the branch path runs regardless of the job's expected output.
 *
 * <p>Per ticket 01c §Step 6 / LLD lines 754-755. The "coherenceScore" of the LLD response shape is
 * proxied by {@code confidence} per ticket §Step 6 ("agent uses {@code confidence} as proxy if no
 * separate field is needed").
 */
@Component
public class CharacterPreservationGate {

  private static final BigDecimal MIN_CHARACTER = BigDecimal.valueOf(0.6);
  private static final BigDecimal MIN_BRANCH_COHERENCE = BigDecimal.valueOf(0.7);

  /**
   * Evaluate the gate. Returns {@code true} when the response is permitted to continue as a branch
   * (high-coherence branch path). Returns {@code false} for a clean pass. Throws when the candidate
   * is rejected.
   */
  public boolean evaluateAndForceBranch(RecipeAdaptationResponse response) {
    if (response == null || response.characterPreservationScore() == null) {
      throw new AdaptationCharacterBreakException("missing characterPreservationScore");
    }
    BigDecimal score = response.characterPreservationScore();
    if (score.compareTo(MIN_CHARACTER) >= 0) {
      return false;
    }
    // Below threshold — escape hatch only when the LLM proposed BRANCH with high coherence.
    if (response.classification() == AdaptationClassification.BRANCH
        && response.confidence() != null
        && response.confidence().compareTo(MIN_BRANCH_COHERENCE) >= 0) {
      return true;
    }
    throw new AdaptationCharacterBreakException(
        "characterPreservationScore " + score + " below 0.6 and not high-coherence BRANCH");
  }
}
