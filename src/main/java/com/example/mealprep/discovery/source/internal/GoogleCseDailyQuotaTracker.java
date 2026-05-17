package com.example.mealprep.discovery.source.internal;

import com.example.mealprep.discovery.domain.entity.DiscoveryGoogleCseUsage;
import com.example.mealprep.discovery.domain.repository.DiscoveryGoogleCseUsageRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Hybrid in-memory + DB-backed Google CSE daily-call counter (01e). The free tier is 100 q/day;
 * losing the count on a runner restart could overrun it, so the count is mirrored to {@code
 * discovery_google_cse_usage} (one row per UTC day) and re-read on startup.
 *
 * <p>Day boundary is UTC midnight. The first call of a new day persists the prior day's final
 * count, then resets the in-memory counter to 0 before recording.
 *
 * <p>Package-internal collaborator of {@code GoogleCustomSearchAdapter}. {@code synchronized}
 * methods — call volume is tiny (≤ a few hundred/day) so coarse locking is fine and keeps the
 * read-modify-write across the in-memory counter + DB atomic.
 */
@Component
public class GoogleCseDailyQuotaTracker {

  private static final Logger log = LoggerFactory.getLogger(GoogleCseDailyQuotaTracker.class);

  private final DiscoveryGoogleCseUsageRepository repository;
  private final Clock clock;

  private LocalDate currentDay;
  private long currentDayCount;

  @Autowired
  public GoogleCseDailyQuotaTracker(DiscoveryGoogleCseUsageRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Crash recovery: seed today's counter from the DB so a restart doesn't reset the budget. */
  @PostConstruct
  synchronized void init() {
    currentDay = today();
    currentDayCount =
        repository.findById(currentDay).map(DiscoveryGoogleCseUsage::getCallCount).orElse(0);
    log.info(
        "google CSE quota tracker initialised for {} at count={}", currentDay, currentDayCount);
  }

  /** Today's call count, after rolling the day over if the UTC date has advanced. */
  public synchronized int todaysCount() {
    rollOverIfNeeded();
    return Math.toIntExact(currentDayCount);
  }

  /** Record one CSE call (success or HTTP failure — Google bills regardless). */
  public synchronized void recordCall() {
    rollOverIfNeeded();
    currentDayCount++;
    persist();
  }

  private void rollOverIfNeeded() {
    LocalDate now = today();
    if (!now.equals(currentDay)) {
      // Prior day's final count is already persisted by its last recordCall(); just reset.
      log.info(
          "google CSE quota day rollover {} (count={}) -> {}", currentDay, currentDayCount, now);
      currentDay = now;
      currentDayCount = 0;
    }
  }

  private void persist() {
    DiscoveryGoogleCseUsage row =
        repository
            .findById(currentDay)
            .orElseGet(
                () -> DiscoveryGoogleCseUsage.builder().day(currentDay).callCount(0).build());
    row.setCallCount(Math.toIntExact(currentDayCount));
    row.setUpdatedAt(Instant.now(clock));
    repository.save(row);
  }

  private LocalDate today() {
    return LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
  }
}
