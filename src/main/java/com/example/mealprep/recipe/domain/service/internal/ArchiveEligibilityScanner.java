package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.event.ArchiveCause;
import com.example.mealprep.recipe.event.RecipeArchivedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily-cron job that flips SYSTEM-catalogue recipes whose {@code last_used_in_plan_at} is older
 * than 90 days (or null = never used) to {@code archived_at != null}. Per LLD §Flow 9 lines 766-772
 * + recipe-01g ticket §{@code ArchiveEligibilityScanner}.
 *
 * <p>{@code MAX_PER_RUN = 1000} and {@code BATCH = 100}: each daily run flips at most 1000 rows,
 * chunked into 10 batches of 100. The query is bounded server-side via {@link PageRequest}; the
 * batch loop publishes one {@link RecipeArchivedEvent} per archived row {@code AFTER_COMMIT}.
 *
 * <p>The bulk update bypasses the {@code @Version} optimistic-lock check — intentional for
 * SYSTEM-catalogue rows which have no concurrent user-writer (per the LLD).
 */
@Component
public class ArchiveEligibilityScanner {

  private static final Logger log = LoggerFactory.getLogger(ArchiveEligibilityScanner.class);

  private static final int BATCH = 100;
  private static final int MAX_PER_RUN = 1000;
  private static final Duration WINDOW = Duration.ofDays(90);

  private final RecipeRepository recipeRepository;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public ArchiveEligibilityScanner(
      RecipeRepository recipeRepository, ApplicationEventPublisher events, Clock clock) {
    this.recipeRepository = recipeRepository;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Scheduled trigger. Default cron: {@code 0 30 3 * * *} (03:30 UTC daily). Override via {@code
   * mealprep.recipe.archive.cron}.
   */
  @Scheduled(cron = "${mealprep.recipe.archive.cron:0 30 3 * * *}")
  public int runScheduled() {
    return runOnce();
  }

  /**
   * Single synchronous scan; returns the number of rows newly transitioned to {@code archived_at !=
   * null}. Re-running the scan immediately produces zero archives (the {@code archivedAt is null}
   * filter excludes already-archived rows).
   */
  @Transactional
  public int runOnce() {
    long start = System.currentTimeMillis();
    Instant now = Instant.now(clock);
    Instant cutoff = now.minus(WINDOW);
    List<UUID> ids =
        recipeRepository.findArchiveEligibleSystemRecipes(cutoff, PageRequest.of(0, MAX_PER_RUN));
    if (ids.isEmpty()) {
      log.info("ArchiveEligibilityScanner: 0 rows eligible (cutoff={})", cutoff);
      return 0;
    }

    int total = 0;
    for (int i = 0; i < ids.size(); i += BATCH) {
      int end = Math.min(i + BATCH, ids.size());
      List<UUID> chunk = ids.subList(i, end);
      int n = recipeRepository.markArchived(chunk, now);
      total += n;
      for (UUID id : chunk) {
        events.publishEvent(
            new RecipeArchivedEvent(id, ArchiveCause.INACTIVITY_3_MONTHS, UUID.randomUUID(), now));
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    log.info(
        "ArchiveEligibilityScanner: archived {} rows (cutoff={}, elapsedMs={})",
        total,
        cutoff,
        elapsed);
    return total;
  }
}
