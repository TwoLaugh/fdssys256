package com.example.mealprep.grocery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.QuoteRequest;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.ProviderUnavailableException;
import com.example.mealprep.grocery.domain.service.internal.providers.QuoteResult;
import com.example.mealprep.grocery.exception.OrderConcurrencyConflictException;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Single-flight concurrency IT (grocery-01e). Two concurrent {@code quote} calls on the same {@code
 * (userId, shoppingListId)} race on the {@code core.LockService} advisory xact-lock: exactly one
 * acquires it (and completes) while the other fails fast with {@link
 * OrderConcurrencyConflictException} (409). After the winner's transaction commits and releases the
 * lock, a re-attempt succeeds. The provider blocks on a latch so both calls overlap inside their
 * transactions, forcing the lock contention.
 */
@SpringBootTest
@Import({TestContainersConfig.class, OrderConcurrencyIT.BlockingProviderConfig.class})
@ActiveProfiles("test")
class OrderConcurrencyIT {

  private static final String PROVIDER = "fake";

  @Autowired private GroceryOrderService orderService;
  @Autowired private BlockingFakeProvider provider;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    provider.release();
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    jdbcTemplate.update("DELETE FROM grocery_order_lines");
    jdbcTemplate.update("DELETE FROM grocery_orders");
    jdbcTemplate.update("DELETE FROM grocery_provider_state");
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
  }

  @Test
  void twoConcurrentQuotes_oneWins_otherGets409_thenLoserCanRetry() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID listId = seedListWithLine(userId);
    orderService.upsertProviderConnection(
        userId, new ProviderConnectionRequest(PROVIDER, true, false, 50));
    UUID orderId = orderService.createDraft(userId, new CreateOrderRequest(listId, PROVIDER)).id();

    // The provider blocks on the latch so the first quote holds the lock while the second arrives.
    provider.arm();
    CountDownLatch bothSubmitted = new CountDownLatch(2);
    AtomicInteger conflicts = new AtomicInteger();
    AtomicInteger successes = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a =
          pool.submit(() -> attempt(userId, orderId, bothSubmitted, conflicts, successes));
      Future<?> b =
          pool.submit(() -> attempt(userId, orderId, bothSubmitted, conflicts, successes));

      // Wait until both threads are inside the service call, then unblock the provider.
      assertThat(bothSubmitted.await(10, TimeUnit.SECONDS)).isTrue();
      provider.release();

      a.get(20, TimeUnit.SECONDS);
      b.get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(successes.get()).as("exactly one quote wins").isEqualTo(1);
    assertThat(conflicts.get()).as("the other gets 409").isEqualTo(1);

    // After the winner's tx committed, the lock released — a fresh draft on the SAME (userId, list)
    // can now acquire it and quote successfully (the loser's scope is no longer blocked). The
    // already-QUOTED original cannot be re-quoted (QUOTED → QUOTED is an illegal edge), so we prove
    // lock-availability with a new draft rather than re-running the same order.
    provider.disarm();
    UUID secondOrderId =
        orderService.createDraft(userId, new CreateOrderRequest(listId, PROVIDER)).id();
    var dto = orderService.quote(userId, new QuoteRequest(secondOrderId));
    assertThat(dto.status().name()).isEqualTo("QUOTED");
  }

  private void attempt(
      UUID userId,
      UUID orderId,
      CountDownLatch bothSubmitted,
      AtomicInteger conflicts,
      AtomicInteger successes) {
    bothSubmitted.countDown();
    try {
      orderService.quote(userId, new QuoteRequest(orderId));
      successes.incrementAndGet();
    } catch (OrderConcurrencyConflictException e) {
      conflicts.incrementAndGet();
    }
  }

  private UUID seedListWithLine(UUID userId) {
    UUID listId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO shopping_lists (id, user_id, household_id, plan_id, plan_generation,"
            + " generated_at, estimated_total_currency, stale_ingredient_count,"
            + " pantry_tracking_enabled, version, created_at, updated_at)"
            + " VALUES (?, ?, NULL, ?, 1, now(), 'GBP', 0, false, 0, now(), now())",
        listId,
        userId,
        UUID.randomUUID());
    jdbcTemplate.update(
        "INSERT INTO shopping_list_lines (id, shopping_list_id, ingredient_mapping_key,"
            + " display_name, requested_quantity, requested_unit, suggested_pack_size_g,"
            + " suggested_pack_count, line_type, is_stale_estimate, fulfilment_status, created_at,"
            + " updated_at) VALUES (?, ?, 'white rice', 'White rice', 1.000, 'kg', 500, 1,"
            + " 'PLANNED_DEMAND', false, 'UNFILLED', now(), now())",
        UUID.randomUUID(),
        listId);
    return listId;
  }

  /** A fake provider whose {@code quote} blocks on a latch so two callers overlap in their txs. */
  static class BlockingFakeProvider extends FakeGroceryProvider {
    private volatile CountDownLatch gate;

    BlockingFakeProvider(ReferencePriceSource referencePriceSource, Clock clock) {
      super(referencePriceSource, clock);
    }

    void arm() {
      this.gate = new CountDownLatch(1);
    }

    void disarm() {
      this.gate = null;
    }

    void release() {
      CountDownLatch g = this.gate;
      if (g != null) {
        g.countDown();
      }
    }

    @Override
    public QuoteResult quote(BasketDraft draft) throws ProviderUnavailableException {
      CountDownLatch g = this.gate;
      if (g != null) {
        try {
          g.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return super.quote(draft);
    }
  }

  @TestConfiguration
  static class BlockingProviderConfig {
    @Bean
    @Primary
    BlockingFakeProvider blockingFakeProvider(
        ReferencePriceSource referencePriceSource, Clock clock) {
      return new BlockingFakeProvider(referencePriceSource, clock);
    }
  }
}
