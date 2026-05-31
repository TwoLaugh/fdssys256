package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.domain.service.internal.UsdaCallTracker;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Unit tests for {@code UsdaCallTracker} (admin/status USDA liveness signal, C-G-032). */
class UsdaCallTrackerTest {

  private final Instant now = Instant.parse("2026-05-31T08:15:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  @Test
  void lastUsdaCallAt_emptyBeforeAnyCall() {
    assertThat(new UsdaCallTracker(clock).lastUsdaCallAt()).isEmpty();
  }

  @Test
  void recordCall_stampsClockInstant() {
    UsdaCallTracker tracker = new UsdaCallTracker(clock);

    tracker.recordCall();

    assertThat(tracker.lastUsdaCallAt()).contains(now);
  }

  @Test
  void recordCall_lastWriteWins() {
    Instant later = Instant.parse("2026-05-31T09:00:00Z");
    // first call at `now`, second at `later` — the tracker reflects the most recent.
    UsdaCallTracker tracker = new UsdaCallTracker(Clock.fixed(now, ZoneOffset.UTC));
    tracker.recordCall();
    UsdaCallTracker later2 = new UsdaCallTracker(Clock.fixed(later, ZoneOffset.UTC));
    later2.recordCall();

    assertThat(later2.lastUsdaCallAt()).contains(later);
  }
}
