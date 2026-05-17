package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.example.mealprep.discovery.domain.entity.DiscoveryGoogleCseUsage;
import com.example.mealprep.discovery.domain.repository.DiscoveryGoogleCseUsageRepository;
import com.example.mealprep.discovery.source.internal.GoogleCseDailyQuotaTracker;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the hybrid in-memory + DB quota tracker: crash-recovery seed from the DB, count
 * persistence, and UTC-midnight rollover driven by an advancing {@link Clock}.
 */
@ExtendWith(MockitoExtension.class)
class GoogleCseDailyQuotaTrackerTest {

  @Mock private DiscoveryGoogleCseUsageRepository repository;

  /** In-memory store mimicking the JPA repo so persistence + rollover are observable. */
  private void wireInMemoryStore(Map<LocalDate, DiscoveryGoogleCseUsage> store) {
    lenient()
        .when(repository.findById(any(LocalDate.class)))
        .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    lenient()
        .when(repository.save(any(DiscoveryGoogleCseUsage.class)))
        .thenAnswer(
            inv -> {
              DiscoveryGoogleCseUsage row = inv.getArgument(0);
              store.put(row.getDay(), row);
              return row;
            });
  }

  private GoogleCseDailyQuotaTracker tracker(Clock clock) throws Exception {
    GoogleCseDailyQuotaTracker t = new GoogleCseDailyQuotaTracker(repository, clock);
    Method init = GoogleCseDailyQuotaTracker.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(t);
    return t;
  }

  @Test
  void init_noRowForToday_counterIsZero() throws Exception {
    wireInMemoryStore(new HashMap<>());
    GoogleCseDailyQuotaTracker t =
        tracker(Clock.fixed(Instant.parse("2026-05-17T09:00:00Z"), ZoneOffset.UTC));

    assertThat(t.todaysCount()).isZero();
  }

  @Test
  void init_existingRowForToday_seedsCounter() throws Exception {
    Map<LocalDate, DiscoveryGoogleCseUsage> store = new HashMap<>();
    store.put(
        LocalDate.parse("2026-05-17"),
        DiscoveryGoogleCseUsage.builder()
            .day(LocalDate.parse("2026-05-17"))
            .callCount(23)
            .updatedAt(Instant.now())
            .build());
    wireInMemoryStore(store);

    GoogleCseDailyQuotaTracker t =
        tracker(Clock.fixed(Instant.parse("2026-05-17T09:00:00Z"), ZoneOffset.UTC));

    assertThat(t.todaysCount()).isEqualTo(23);
  }

  @Test
  void recordCall_incrementsAndPersists() throws Exception {
    Map<LocalDate, DiscoveryGoogleCseUsage> store = new HashMap<>();
    wireInMemoryStore(store);
    GoogleCseDailyQuotaTracker t =
        tracker(Clock.fixed(Instant.parse("2026-05-17T09:00:00Z"), ZoneOffset.UTC));

    t.recordCall();
    t.recordCall();

    assertThat(t.todaysCount()).isEqualTo(2);
    assertThat(store.get(LocalDate.parse("2026-05-17")).getCallCount()).isEqualTo(2);
  }

  @Test
  void recordCall_afterUtcMidnight_rollsOverToNewDay() throws Exception {
    Map<LocalDate, DiscoveryGoogleCseUsage> store = new HashMap<>();
    wireInMemoryStore(store);
    AdvancingClock clock = new AdvancingClock(Instant.parse("2026-05-17T23:59:00Z"));
    GoogleCseDailyQuotaTracker t = tracker(clock);

    t.recordCall();
    t.recordCall();
    assertThat(t.todaysCount()).isEqualTo(2);
    assertThat(store.get(LocalDate.parse("2026-05-17")).getCallCount()).isEqualTo(2);

    clock.advance(Duration.ofMinutes(2)); // crosses UTC midnight into 2026-05-18

    t.recordCall();
    assertThat(t.todaysCount()).isEqualTo(1);
    assertThat(store.get(LocalDate.parse("2026-05-18")).getCallCount()).isEqualTo(1);
    // prior day's final count preserved
    assertThat(store.get(LocalDate.parse("2026-05-17")).getCallCount()).isEqualTo(2);
  }

  private static final class AdvancingClock extends Clock {
    private Instant instant;

    AdvancingClock(Instant initial) {
      this.instant = initial;
    }

    void advance(Duration d) {
      this.instant = this.instant.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
