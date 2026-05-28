package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.api.dto.CancelOrderRequest;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.domain.service.PriceHistoryService;
import com.example.mealprep.grocery.domain.service.internal.providers.GroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin {@code @Scheduled} driver layer for grocery-01g — the three optional background jobs that
 * complete the grocery tier ladder (no @pending E2E flips; ships for completion's sake against
 * {@code FakeGroceryProvider} until the deferred Tesco provider lands). Per lld/grocery.md §Flow 6
 * (lines 941-953), §state machine (line 796), §SchedulerConfig (line 998).
 *
 * <p>Three cron triggers:
 *
 * <ul>
 *   <li><b>Weekly refresh</b> ({@code refresh-cron}, default 04:00 Sundays UK) — for every user
 *       with {@code scheduled_refresh_enabled = true}, fan out an {@code @Async} per-user call to
 *       {@link PriceHistoryService#runScheduledBackgroundRefresh(java.util.UUID)}. One user's
 *       failure must not abort the run (LLD line 981); each user's refresh is its own bounded
 *       transaction inside the service method.
 *   <li><b>Hourly order-status poll</b> ({@code order-status-cron}, default top of every hour) —
 *       advance active {@code CONFIRMED} orders to {@code DELIVERED} via the provider's status feed
 *       (which triggers 01f's substitution persistence + reconcile-or-block flow). Sweep {@code
 *       PROVIDER_UNAVAILABLE} orders: retry {@code provider.quote} once an hour; after {@link
 *       GroceryConfig.OrderConfig#providerUnavailableRetryHours()} consecutive failed hours
 *       (default 24) auto-cancel with {@code cancel_reason = "provider_unavailable_24h"} (LLD line
 *       923, GROC-27).
 *   <li><b>Daily archival sweep</b> ({@code archive-cron}, default 05:00) — flip {@code reconciled
 *       → archived} where {@code reconciled_at} is at or before 12 months ago (LLD line 796;
 *       GROC-35 boundary).
 * </ul>
 *
 * <p>This component is a Spring driver only — every meaningful behaviour is delegated to a service
 * method ({@link PriceHistoryService}, {@link GroceryOrderService}) so the bodies stay
 * mutation-testable through the existing service-impl tests / ITs.
 */
@Component
public class GroceryScheduledJobs {

  private static final Logger log = LoggerFactory.getLogger(GroceryScheduledJobs.class);

  /** Used on auto-cancellations after the {@code provider_unavailable_24h} retry horizon. */
  static final String CANCEL_REASON_PROVIDER_UNAVAILABLE_24H = "provider_unavailable_24h";

  private final GroceryOrderDataGateway gateway;
  private final GroceryOrderService groceryOrderService;
  private final PriceHistoryService priceHistoryService;
  private final ObjectProvider<GroceryProvider> providers;
  private final GroceryConfig groceryConfig;
  private final Clock clock;

  public GroceryScheduledJobs(
      GroceryOrderDataGateway gateway,
      GroceryOrderService groceryOrderService,
      PriceHistoryService priceHistoryService,
      ObjectProvider<GroceryProvider> providers,
      GroceryConfig groceryConfig,
      Clock clock) {
    this.gateway = gateway;
    this.groceryOrderService = groceryOrderService;
    this.priceHistoryService = priceHistoryService;
    this.providers = providers;
    this.groceryConfig = groceryConfig;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Weekly refresh — LLD lines 947-952
  // ---------------------------------------------------------------------------------------------

  /**
   * Weekly fan-out. The candidate set is read on the scheduler thread (one cheap query); each
   * user's refresh is dispatched via {@link #refreshOneUser} which is {@code @Async} so a slow or
   * failing user can't hold up the others (LLD line 981). The per-user method owns its own
   * transaction.
   */
  @Scheduled(cron = "${mealprep.grocery.scheduler.refresh-cron:0 0 4 * * SUN}")
  public void weeklyRefresh() {
    List<GroceryProviderState> states = gateway.findProviderStatesWithScheduledRefreshEnabled();
    if (states.isEmpty()) {
      return;
    }
    log.info("grocery scheduled refresh: fanning out to {} user(s)", states.size());
    for (GroceryProviderState state : states) {
      refreshOneUser(state.getUserId());
    }
  }

  /**
   * Per-user refresh worker. {@code @Async} so a slow run on one user doesn't sequence the next;
   * the catch-all converts any failure into a logged error rather than a propagated exception (a
   * single user's crash must not abort the fan-out). Uses the default {@code @Async} executor —
   * {@code DefaultAsyncConfig} is the bounded pool that catches the project-wide unqualified
   * {@code @Async} fall-through.
   */
  @Async
  public void refreshOneUser(java.util.UUID userId) {
    try {
      priceHistoryService.runScheduledBackgroundRefresh(userId);
    } catch (RuntimeException ex) {
      log.warn(
          "grocery scheduled refresh failed for user {}: {} ({})",
          userId,
          ex.getMessage(),
          ex.getClass().getSimpleName());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Hourly status / unavailable-retry poll — LLD lines 911, 923
  // ---------------------------------------------------------------------------------------------

  /**
   * Top-of-every-hour sweep. Two passes:
   *
   * <ol>
   *   <li>For each {@code CONFIRMED} order with a {@code providerOrderId} → call {@code
   *       provider.checkStatus}; on delivery, advance via {@link GroceryOrderService#markDelivered}
   *       which fires 01f's substitution + reconcile flow. Provider unavailability → log and
   *       continue (one provider's outage shouldn't stall the whole sweep).
   *   <li>For each {@code PROVIDER_UNAVAILABLE} order → retry {@code provider.quote}; if the
   *       provider has been unavailable for more than {@code providerUnavailableRetryHours} since
   *       the order's {@code updatedAt}, auto-cancel with {@code provider_unavailable_24h}.
   * </ol>
   */
  @Scheduled(cron = "${mealprep.grocery.scheduler.order-status-cron:0 0 * * * *}")
  public void hourlyStatusPoll() {
    pollConfirmedOrders();
    sweepProviderUnavailableOrders();
  }

  private void pollConfirmedOrders() {
    List<GroceryOrder> confirmed = gateway.findOrdersByStatus(GroceryOrderStatus.CONFIRMED);
    for (GroceryOrder order : confirmed) {
      if (order.getProviderOrderId() == null) {
        continue;
      }
      GroceryProvider provider = providerFor(order.getProviderKey());
      if (provider == null) {
        continue;
      }
      try {
        // refreshStatus does the FULL DELIVERED-path inside one service transaction (LLD line
        // 911): call provider.checkStatus, persist any provider-surfaced substitutions as
        // pending_user_review proposals via SubstitutionPersister, advance CONFIRMED → DELIVERED,
        // and call tryReconcile (which only proceeds when no proposals remain outstanding).
        // Going through markDelivered here would lose the substitutions because that path takes
        // no OrderStatus argument and persists none.
        groceryOrderService.refreshStatus(order.getUserId(), order.getId());
      } catch (com.example.mealprep.grocery.exception.ProviderUnavailableException ex) {
        log.info(
            "hourly status poll: provider {} unavailable for order {} ({})",
            order.getProviderKey(),
            order.getId(),
            ex.getMessage());
      } catch (RuntimeException ex) {
        log.warn(
            "hourly status poll: unexpected failure for order {}: {}",
            order.getId(),
            ex.getMessage());
      }
    }
  }

  private void sweepProviderUnavailableOrders() {
    List<GroceryOrder> stuck = gateway.findOrdersByStatus(GroceryOrderStatus.PROVIDER_UNAVAILABLE);
    if (stuck.isEmpty()) {
      return;
    }
    int retryHours = groceryConfig.order().providerUnavailableRetryHours();
    Instant cutoff = clock.instant().minus(java.time.Duration.ofHours(retryHours));
    for (GroceryOrder order : stuck) {
      Instant marker = order.getUpdatedAt();
      if (marker != null && marker.isBefore(cutoff)) {
        // Past the retry horizon — auto-cancel.
        autoCancelProviderUnavailable(order);
        continue;
      }
      // Within the horizon — best-effort retry: a successful quote will be driven by the user
      // re-running the lifecycle; here we only confirm the provider is reachable so the order can
      // progress. We do NOT silently advance the order to QUOTED because that would bypass the
      // single-flight + per-user-confirm path the LLD requires (line 909). The retry is a
      // reachability probe; the timestamp on the order is bumped by the recorder if it changes.
      attemptProviderReachable(order);
    }
  }

  private void autoCancelProviderUnavailable(GroceryOrder order) {
    try {
      groceryOrderService.cancel(
          order.getUserId(),
          new CancelOrderRequest(order.getId(), CANCEL_REASON_PROVIDER_UNAVAILABLE_24H));
      log.info(
          "auto-cancelled order {} after {}h provider_unavailable",
          order.getId(),
          groceryConfig.order().providerUnavailableRetryHours());
    } catch (RuntimeException ex) {
      log.warn("auto-cancel failed for order {}: {}", order.getId(), ex.getMessage());
    }
  }

  private void attemptProviderReachable(GroceryOrder order) {
    GroceryProvider provider = providerFor(order.getProviderKey());
    if (provider == null || order.getProviderOrderId() == null) {
      return;
    }
    try {
      provider.checkStatus(order.getProviderOrderId());
    } catch (ProviderUnavailableException ignored) {
      // Still down — leave the order alone; the horizon timer is the gate, not this reachability
      // probe. We deliberately don't bump updatedAt here; the failure-recorder did that when the
      // order entered PROVIDER_UNAVAILABLE.
    } catch (RuntimeException ex) {
      log.warn(
          "provider reachability probe failed for order {}: {}", order.getId(), ex.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Daily archival sweep — LLD line 796
  // ---------------------------------------------------------------------------------------------

  /**
   * Daily archival sweep. {@code reconciled → archived} where {@code reconciled_at} is at or before
   * 12 months ago (GROC-35 boundary — exactly 12 months archives, 11 months does not). Runs in a
   * single transaction; the 12-month comparison is inclusive on the cutoff.
   *
   * <p>The 12-month cutoff is computed with calendar arithmetic ({@link Period#ofMonths(int)})
   * rather than a fixed-duration approximation (e.g. {@code 365 days}) so the boundary is exact
   * across leap years.
   */
  @Scheduled(cron = "${mealprep.grocery.scheduler.archive-cron:0 0 5 * * *}")
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void dailyArchivalSweep() {
    Instant now = clock.instant();
    Instant cutoff =
        LocalDateTime.ofInstant(now, ZoneOffset.UTC)
            .minus(Period.ofMonths(12))
            .toInstant(ZoneOffset.UTC);
    List<GroceryOrder> candidates = gateway.findReconciledOlderThan(cutoff);
    int archived = 0;
    for (GroceryOrder order : candidates) {
      order.setStatus(GroceryOrderStatus.ARCHIVED);
      gateway.saveOrder(order);
      archived++;
    }
    if (archived > 0) {
      log.info("grocery archival sweep: archived {} reconciled order(s)", archived);
    }
  }

  private GroceryProvider providerFor(String providerKey) {
    if (providerKey == null) {
      return null;
    }
    return providers
        .orderedStream()
        .filter(p -> providerKey.equals(p.providerKey()))
        .findFirst()
        .orElse(null);
  }
}
