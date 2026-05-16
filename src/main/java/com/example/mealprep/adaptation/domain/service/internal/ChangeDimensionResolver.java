package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.AdaptationContext;
import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure-function resolver that maps the AI's diff + trigger payload to a {@link ChangeDimension}.
 *
 * <p>Per ticket 01c §Step 7 / LLD line 245 — unmatched values surface a WARN log and fall back to
 * {@link ChangeDimension#GENERAL}.
 */
@Component
public class ChangeDimensionResolver {

  private static final Logger LOG = LoggerFactory.getLogger(ChangeDimensionResolver.class);
  private static final BigDecimal NEG_HALF = BigDecimal.valueOf(-0.5);

  /** Resolve the dimension; never throws. */
  public ChangeDimension resolve(
      AdaptationJob job, AdaptationContext context, RecipeAdaptationResponse response) {
    if (job.getSource() == JobSource.FEEDBACK && context.ratingDelta() != null) {
      FeedbackJobRequest.RatingDeltaDto rd = context.ratingDelta();
      if (rd.taste() != null && rd.taste().compareTo(NEG_HALF) < 0) {
        return dimensionFromTasteDiff(response);
      }
      if (rd.effortWorthIt() != null && rd.effortWorthIt().compareTo(NEG_HALF) < 0) {
        return ChangeDimension.METHOD_SIMPLIFICATION;
      }
      if (rd.portionFit() != null && rd.portionFit().compareTo(NEG_HALF) < 0) {
        return ChangeDimension.PORTION_SIZE;
      }
    }
    // Default — inspect diff if available.
    RecipeDiffDto diff = response == null ? null : response.refinedDiff();
    if (diff != null) {
      if (diff.ingredientChanges() != null && !diff.ingredientChanges().isEmpty()) {
        return ChangeDimension.PROTEIN;
      }
      if (diff.methodChanges() != null && !diff.methodChanges().isEmpty()) {
        return ChangeDimension.METHOD_SIMPLIFICATION;
      }
    }
    LOG.warn(
        "ChangeDimensionResolver fell back to GENERAL for job={} source={}",
        job.getId(),
        job.getSource());
    return ChangeDimension.GENERAL;
  }

  private ChangeDimension dimensionFromTasteDiff(RecipeAdaptationResponse response) {
    if (response == null || response.refinedDiff() == null) {
      return ChangeDimension.SALT_LEVEL;
    }
    // Without per-line semantic flags we use SALT_LEVEL as the default for taste-driven feedback.
    return ChangeDimension.SALT_LEVEL;
  }
}
