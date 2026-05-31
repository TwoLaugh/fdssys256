package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily cron that auto-expires accepted health directives whose {@code auto_expires_at} has passed
 * (nutrition-3 / LLD Flow 8 line 1022): it transitions ACCEPTED → EXPIRED and instructs the source
 * module to revert any temporary effect (e.g. a 6-week egg-elimination hard constraint via {@code
 * PreferenceUpdateService.removeTemporaryConstraint}).
 *
 * <p>The cron expression is parameterised on {@code mealprep.nutrition.directive-expiry-sweep-cron}
 * (default {@code 0 0 4 * * *}, matching the LLD) so test runs can override it. Mirrors the
 * established sweep pattern ({@code adaptation.PendingChangeExpirySweepScheduler}). Requires the
 * application-level {@code @EnableScheduling}.
 */
@Component
public class DirectiveExpirySweepScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(DirectiveExpirySweepScheduler.class);

  private final NutritionUpdateService nutritionUpdateService;

  public DirectiveExpirySweepScheduler(NutritionUpdateService nutritionUpdateService) {
    this.nutritionUpdateService = nutritionUpdateService;
  }

  @Scheduled(cron = "${mealprep.nutrition.directive-expiry-sweep-cron:0 0 4 * * *}")
  public void sweep() {
    int touched = nutritionUpdateService.sweepExpiredDirectives();
    if (touched > 0) {
      LOG.info("directive auto-expiry sweep transitioned {} directive(s) to EXPIRED", touched);
    }
  }
}
