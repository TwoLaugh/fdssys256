package com.example.mealprep.provisions.domain.service.internal;

import com.example.mealprep.provisions.domain.repository.CookEventDedupeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily sweep over {@code provision_cook_event_dedupe} — deletes rows older than 24h to keep the
 * idempotency table bounded. See LLD line 623.
 */
@Component
class CookEventDedupeSweeper {

  private static final Logger log = LoggerFactory.getLogger(CookEventDedupeSweeper.class);

  private static final Duration RETENTION = Duration.ofHours(24);

  private final CookEventDedupeRepository repository;
  private final Clock clock;

  CookEventDedupeSweeper(CookEventDedupeRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Daily at 04:00 UTC by default; override via {@code mealprep.provision.cook-dedupe.cron}. */
  @Scheduled(cron = "${mealprep.provision.cook-dedupe.cron:0 0 4 * * *}")
  @Transactional
  public void sweep() {
    Instant cutoff = Instant.now(clock).minus(RETENTION);
    int deleted = repository.deleteOlderThan(cutoff);
    if (deleted > 0) {
      log.info("cook-event dedupe sweep deleted={} cutoff={}", deleted, cutoff);
    }
  }
}
