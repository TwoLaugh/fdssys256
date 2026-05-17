package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.domain.service.AdaptationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily cron that flips expired PENDING changes to EXPIRED. The expression is parameterised on
 * {@code mealprep.adaptation.pending-expiry-sweep-cron} (default {@code 0 0 4 * * *}, matching
 * {@code AdaptationConfig.pendingExpirySweepCron()}) so test runs can override it.
 *
 * <p>Per ticket 01f §sweepExpiredPendingChanges + {@code lld/adaptation-pipeline.md} line 155.
 */
@Component
public class PendingChangeExpirySweepScheduler {

  private static final Logger LOG =
      LoggerFactory.getLogger(PendingChangeExpirySweepScheduler.class);

  private final AdaptationService adaptationService;

  public PendingChangeExpirySweepScheduler(AdaptationService adaptationService) {
    this.adaptationService = adaptationService;
  }

  @Scheduled(cron = "${mealprep.adaptation.pending-expiry-sweep-cron:0 0 4 * * *}")
  public void sweep() {
    int touched = adaptationService.sweepExpiredPendingChanges();
    if (touched > 0) {
      LOG.info("pending-change expiry sweep flipped {} row(s) to EXPIRED", touched);
    }
  }
}
