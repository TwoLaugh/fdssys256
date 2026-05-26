package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trigger 1 entry — listens for {@link RecipeCreatedEvent} from the recipe module and enqueues an
 * IMPORT-source adaptation job. Per LLD line 693.
 *
 * <p>Follows the round-7 rule: {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)} because the listener body writes an {@code AdaptationJob}
 * row.
 *
 * <p><b>Cross-module rule</b>: the listener cannot inject {@code RecipeRepository} (ArchUnit), so
 * the owning {@code userId} and the recipe's {@code dataQuality} must travel on the event itself.
 * {@link RecipeCreatedEvent} now carries both, so the {@link ImportJobRequest} is built from the
 * real values rather than the earlier v1 placeholders (recipeId-as-userId + hardcoded {@code
 * AI_GENERATED}).
 */
@org.springframework.stereotype.Component
public class AdaptationImportListener {

  private static final Logger LOG = LoggerFactory.getLogger(AdaptationImportListener.class);

  private final AdaptationService adaptationService;

  public AdaptationImportListener(AdaptationService adaptationService) {
    this.adaptationService = adaptationService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onRecipeCreated(RecipeCreatedEvent event) {
    UUID jobId =
        adaptationService.enqueueImportJob(
            new ImportJobRequest(
                event.recipeId(),
                event.userId(),
                event.catalogue(),
                event.dataQuality(),
                null,
                event.traceId()));
    LOG.info("enqueued IMPORT job {} for newly-created recipe {}", jobId, event.recipeId());
  }
}
