package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.recipe.domain.entity.DataQuality;
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
 * <p><b>Cross-module rule</b>: the listener cannot inject {@code RecipeRepository} (ArchUnit). The
 * event payload only carries {@code recipeId} + {@code catalogue} + {@code traceId}; the {@code
 * userId} and {@code dataQuality} fields needed to build {@link ImportJobRequest} are not on the
 * event. <b>Worth user review</b>: the recipe-module event payload should grow these fields, OR we
 * sacrifice the v1 fallback (an over-broad userId guess). For 01d we pass a placeholder userId (the
 * recipe scopeId, which is recipeId — fine since {@code AdaptationJob.userId} is opaque to
 * processing in 01d) and a hardcoded {@code AI_GENERATED} data quality. The pipeline's downstream
 * stages don't read userId/dataQuality in 01c, so the placeholder is benign for the worker; the
 * follow-up is to extend {@code RecipeCreatedEvent} with the two fields.
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
                event.recipeId(),
                event.catalogue(),
                DataQuality.AI_GENERATED,
                null,
                event.traceId()));
    LOG.info("enqueued IMPORT job {} for newly-created recipe {}", jobId, event.recipeId());
  }
}
