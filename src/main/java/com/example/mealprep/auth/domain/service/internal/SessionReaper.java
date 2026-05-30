package com.example.mealprep.auth.domain.service.internal;

import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly session reaper (LLD Flow 6). Hard-deletes {@code auth_sessions} rows that are already
 * unusable — either past {@code expires_at}, or revoked/expired before {@code now −
 * mealprep.auth.session.retain-revoked-for} (default 7 days). The {@code LoginAttempt} table keeps
 * the longer-term audit trail, so reaping sessions loses no security history.
 *
 * <p>The cron is parameterised on {@code mealprep.auth.session.reaper-cron} (default {@code 0 15 3
 * * * *}, 03:15 nightly); the test profile sets a never-matching cron so the trigger never
 * auto-fires during a test run — {@link SessionReaperIT} drives {@link #sweep()} directly. Mirrors
 * the {@code DispatchLogCleanupScheduler} pattern: a {@code @Scheduled} wrapper plus an on-demand,
 * synchronous {@link #sweep()} for deterministic testing.
 *
 * <p>Safe to run mid-request: every row it deletes is already rejected by {@code
 * SessionAuthenticationFilter} (expired / revoked) before any business logic runs.
 */
@Component
public class SessionReaper {

  private static final Logger log = LoggerFactory.getLogger(SessionReaper.class);

  private final SessionRepository sessionRepository;
  private final AuthProperties authProperties;
  private final Clock clock;

  public SessionReaper(
      SessionRepository sessionRepository, AuthProperties authProperties, Clock clock) {
    this.sessionRepository = sessionRepository;
    this.authProperties = authProperties;
    this.clock = clock;
  }

  /** Daily 03:15 by default; override via {@code mealprep.auth.session.reaper-cron}. */
  @Scheduled(cron = "${mealprep.auth.session.reaper-cron:0 15 3 * * *}")
  @Transactional
  public int runScheduled() {
    return sweep();
  }

  /**
   * Single synchronous sweep. Hard-deletes sessions past their absolute expiry, and revoked
   * sessions whose {@code revoked_at} precedes the retention cutoff. Returns the number of rows
   * deleted.
   */
  @Transactional
  public int sweep() {
    Instant cutoff = Instant.now(clock).minus(authProperties.session().retainRevokedFor());
    int deleted = sessionRepository.deleteExpiredAndRevokedBefore(cutoff);
    if (deleted > 0) {
      log.info("session reaper deleted={} cutoff={}", deleted, cutoff);
    }
    return deleted;
  }
}
