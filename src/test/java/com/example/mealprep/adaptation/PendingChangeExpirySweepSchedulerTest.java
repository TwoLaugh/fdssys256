package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.internal.PendingChangeExpirySweepScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit coverage for {@link PendingChangeExpirySweepScheduler#sweep} — NO_COVERAGE before. The L31
 * {@code touched > 0} conditional gates ONLY a LOG.info, so the ConditionalsBoundary ({@code > 0}
 * -> {@code >= 0}) and NegateConditionals mutants are observable only via the log. We always assert
 * the delegate runs, and assert exact INFO-log presence around the boundary value 0. {@code
 * AdaptationService} is a cross-boundary interface — mocking is appropriate.
 */
class PendingChangeExpirySweepSchedulerTest {

  private final AdaptationService service = mock(AdaptationService.class);
  private final PendingChangeExpirySweepScheduler scheduler =
      new PendingChangeExpirySweepScheduler(service);

  private ListAppender<ILoggingEvent> appender;
  private Logger schedLogger;

  @BeforeEach
  void attach() {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    schedLogger = ctx.getLogger(PendingChangeExpirySweepScheduler.class);
    schedLogger.setLevel(Level.INFO);
    appender = new ListAppender<>();
    appender.start();
    schedLogger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    schedLogger.detachAppender(appender);
  }

  private long infoCount() {
    return appender.list.stream().filter(e -> e.getLevel() == Level.INFO).count();
  }

  @Test
  void sweep_always_delegates_to_the_service() {
    when(service.sweepExpiredPendingChanges()).thenReturn(0);
    scheduler.sweep();
    verify(service).sweepExpiredPendingChanges();
  }

  @Test
  void sweep_logs_info_when_rows_were_flipped() {
    when(service.sweepExpiredPendingChanges()).thenReturn(7);
    scheduler.sweep();
    assertThat(infoCount()).isEqualTo(1L);
    assertThat(appender.list.get(0).getFormattedMessage())
        .contains("pending-change expiry sweep flipped 7 row(s) to EXPIRED");
  }

  @Test
  void sweep_does_not_log_when_zero_rows_flipped() {
    // touched == 0: original SKIPS the log. ConditionalsBoundary (`>= 0`) or NegateConditionals
    // would log here — assert zero INFO records to kill both mutants.
    when(service.sweepExpiredPendingChanges()).thenReturn(0);
    scheduler.sweep();
    assertThat(infoCount()).isZero();
  }
}
