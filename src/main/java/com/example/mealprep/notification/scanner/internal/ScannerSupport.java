package com.example.mealprep.notification.scanner.internal;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Common base for the notification/01b scheduled scanners. Provides the shared collaborators every
 * scanner needs — an injected {@link Clock} (so tests advance time via {@code Clock.fixed} instead
 * of real time), the {@link ApplicationEventPublisher} the scanner emits producer events through,
 * and a structured logger keyed by the scanner name.
 *
 * <p>Scanners read {@code clock.instant()} via {@link #now()} and <em>never</em> {@code
 * Instant.now()} — this is the seam the {@code *ScannerTest} unit tests pin time on, and it is
 * grep-verified by the edge-case checklist.
 *
 * <p>The {@code @Scheduled} trigger on each subclass is the production entry-point; it delegates to
 * a package-visible {@code scan()} method the tests invoke directly (the cron is set to a
 * far-future expression in the test profile so it never auto-fires during a test run).
 */
public abstract class ScannerSupport {

  /** Logger keyed by the concrete scanner name, e.g. {@code scanner=ExpiryWarningScanner}. */
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;

  protected ScannerSupport(Clock clock, ApplicationEventPublisher eventPublisher) {
    this.clock = clock;
    this.eventPublisher = eventPublisher;
  }

  /** Current instant per the injected clock — the single time seam for every scanner. */
  protected Instant now() {
    return clock.instant();
  }

  /** The injected clock (subclasses needing zone-aware date math read it directly). */
  protected Clock clock() {
    return clock;
  }

  /**
   * Publish a producer event; Spring delivers it {@code AFTER_COMMIT} to the notification listener.
   */
  protected void publish(Object event) {
    eventPublisher.publishEvent(event);
  }

  /**
   * Short scanner name used as a structured-log key and a metric tag. Defaults to the simple class
   * name (e.g. {@code ExpiryWarningScanner}).
   */
  protected String scannerName() {
    return getClass().getSimpleName();
  }
}
