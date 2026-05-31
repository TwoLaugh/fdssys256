package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.domain.service.NutritionOperationalStatusService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * In-memory tracker of the last outbound USDA call, and the {@link
 * NutritionOperationalStatusService} implementation that exposes it. {@code UsdaApiClient} calls
 * {@link #recordCall()} on each search; the system-status endpoint reads {@link #lastUsdaCallAt()}.
 *
 * <p>Process-local by design: a "last call" timestamp is an operational liveness signal, not
 * durable state — single-instance v1 has exactly one process, and the value resetting on restart is
 * the correct semantics (there have been no calls since this process started). No persistence, no
 * extra table.
 */
@Component
public class UsdaCallTracker implements NutritionOperationalStatusService {

  private final Clock clock;
  private final AtomicReference<Instant> lastCallAt = new AtomicReference<>();

  public UsdaCallTracker(Clock clock) {
    this.clock = clock;
  }

  /** Stamp "now" as the most recent USDA call. Invoked by {@code UsdaApiClient} on each search. */
  public void recordCall() {
    lastCallAt.set(Instant.now(clock));
  }

  @Override
  public Optional<Instant> lastUsdaCallAt() {
    return Optional.ofNullable(lastCallAt.get());
  }
}
