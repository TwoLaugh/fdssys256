package com.example.mealprep.grocery.domain.service.internal.providers;

import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.grocery.domain.entity.AutomationFailureRecord;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource;
import com.example.mealprep.grocery.domain.service.ReferencePriceSource.ReferencePrice;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic, test-scoped {@link GroceryProvider} (grocery-01e). Lives in the providers pocket
 * (the {@code GroceryBoundaryTest} provider-pocket rule). Quote prices DERIVE from 01c's {@link
 * ReferencePriceSource} ({@code referencePrice(key).unitPence × packCount}) so a quote against the
 * fake returns realistic numbers rather than arbitrary constants.
 *
 * <p>An injectable failure-mode toggle raises {@link ProviderUnavailableException} / {@link
 * ProviderPartialFailureException} / {@link AiUnavailableException} for the negative-path tests
 * (GROC-22 / 25 / 27 / 28). Registered as a bean via a test {@code @Configuration} (or constructed
 * directly in unit tests); NOT a {@code @Component} so it never ships to production.
 */
public class FakeGroceryProvider implements GroceryProvider {

  /** What the fake should do on the next quote/place call. */
  public enum FailureMode {
    NONE,
    UNAVAILABLE,
    PARTIAL,
    AI_UNAVAILABLE
  }

  public static final String PROVIDER_KEY = "fake";

  private final ReferencePriceSource referencePriceSource;
  private final Clock clock;

  private volatile FailureMode failureMode = FailureMode.NONE;
  private volatile String unavailableReason = "provider_down";

  // checkStatus tuning (grocery-01f): when delivered==true, checkStatus reports DELIVERED and
  // surfaces the configured substitutions (the realistic delivery-with-substitutions path that
  // persists proposals via SubstitutionPersister).
  private volatile boolean delivered = false;
  private volatile List<SubstitutionProposal> substitutions = List.of();

  public FakeGroceryProvider(ReferencePriceSource referencePriceSource, Clock clock) {
    this.referencePriceSource = referencePriceSource;
    this.clock = clock;
  }

  /** Set the failure mode the NEXT quote/place call exercises. */
  public void setFailureMode(FailureMode mode) {
    this.failureMode = mode;
  }

  public void setUnavailableReason(String reason) {
    this.unavailableReason = reason;
  }

  /** Make the NEXT {@code checkStatus} report DELIVERED (the refresh-status auto-deliver path). */
  public void setDelivered(boolean delivered) {
    this.delivered = delivered;
  }

  /** Substitutions {@code checkStatus} surfaces when {@link #setDelivered(boolean)} is true. */
  public void setSubstitutions(List<SubstitutionProposal> substitutions) {
    this.substitutions = substitutions == null ? List.of() : List.copyOf(substitutions);
  }

  /** Reset to the happy path. */
  public void reset() {
    this.failureMode = FailureMode.NONE;
    this.unavailableReason = "provider_down";
    this.delivered = false;
    this.substitutions = List.of();
  }

  @Override
  public String providerKey() {
    return PROVIDER_KEY;
  }

  @Override
  public QuoteResult quote(BasketDraft draft) throws ProviderUnavailableException {
    applyPreFlightFailures("quote");

    Instant now = clock.instant();
    Map<UUID, QuoteLineResult> lineResults = new LinkedHashMap<>();
    int total = 0;
    for (BasketDraftLine line : draft.lines()) {
      int packCount = line.packCountRequested() != null ? line.packCountRequested() : 1;
      int unitPence = referenceUnitPence(line.ingredientMappingKey());
      int linePence = unitPence * Math.max(packCount, 1);
      total += linePence;
      lineResults.put(
          line.groceryOrderLineId(),
          new QuoteLineResult(
              OrderLineStatus.ADDED,
              "fake-sku-" + line.ingredientMappingKey(),
              unitPence,
              packCount,
              null));
    }
    return new QuoteResult("fake-order-" + draft.groceryOrderId(), lineResults, total, "GBP", now);
  }

  @Override
  public PlaceOrderResult placeOrder(BasketDraft draft)
      throws ProviderUnavailableException, ProviderPartialFailureException {
    // Pre-flight: UNAVAILABLE / AI_UNAVAILABLE raise before any line is placed.
    if (failureMode == FailureMode.UNAVAILABLE) {
      throw new ProviderUnavailableException(PROVIDER_KEY, unavailableReason, "fake provider down");
    }
    if (failureMode == FailureMode.AI_UNAVAILABLE) {
      throw new AiUnavailableException("fake AI cost cap reached");
    }

    Instant now = clock.instant();
    String providerOrderId = "fake-order-" + draft.groceryOrderId();
    String confirmLink = "https://fake.example/confirm/" + draft.groceryOrderId();

    if (failureMode == FailureMode.PARTIAL) {
      Map<UUID, OrderLineStatus> statuses = new LinkedHashMap<>();
      List<AutomationFailureRecord> failures = new ArrayList<>();
      boolean first = true;
      for (BasketDraftLine line : draft.lines()) {
        // Half-fail: the first line is unavailable, the rest are added (a partial place).
        if (first && draft.lines().size() > 1) {
          statuses.put(line.groceryOrderLineId(), OrderLineStatus.UNAVAILABLE);
          failures.add(
              new AutomationFailureRecord(
                  "place", "item unavailable: " + line.ingredientMappingKey(), now));
          first = false;
        } else {
          statuses.put(line.groceryOrderLineId(), OrderLineStatus.ADDED);
          first = false;
        }
      }
      PlaceOrderResult partial =
          new PlaceOrderResult(providerOrderId, confirmLink, statuses, true, failures, now);
      throw new ProviderPartialFailureException("fake partial place", partial);
    }

    // Happy path: all lines ADDED.
    Map<UUID, OrderLineStatus> statuses = new LinkedHashMap<>();
    for (BasketDraftLine line : draft.lines()) {
      statuses.put(line.groceryOrderLineId(), OrderLineStatus.ADDED);
    }
    return new PlaceOrderResult(providerOrderId, confirmLink, statuses, false, List.of(), now);
  }

  @Override
  public OrderStatus checkStatus(String providerOrderId) throws ProviderUnavailableException {
    if (failureMode == FailureMode.UNAVAILABLE) {
      throw new ProviderUnavailableException(PROVIDER_KEY, unavailableReason, "fake provider down");
    }
    Instant now = clock.instant();
    if (delivered) {
      // Delivery report: DELIVERED + any configured substitutions (persisted as proposals).
      return new OrderStatus(
          GroceryOrderStatus.DELIVERED,
          "delivered",
          now.plusSeconds(3600),
          now.plusSeconds(7200),
          null,
          null,
          substitutions,
          now);
    }
    return new OrderStatus(
        GroceryOrderStatus.CONFIRMED,
        "confirmed",
        now.plusSeconds(3600),
        now.plusSeconds(7200),
        null,
        null,
        List.of(),
        now);
  }

  @Override
  public void cancel(String providerOrderId) throws ProviderUnavailableException {
    if (failureMode == FailureMode.UNAVAILABLE) {
      throw new ProviderUnavailableException(PROVIDER_KEY, unavailableReason, "fake provider down");
    }
    // No-op for the fake.
  }

  private void applyPreFlightFailures(String step) throws ProviderUnavailableException {
    if (failureMode == FailureMode.UNAVAILABLE) {
      throw new ProviderUnavailableException(PROVIDER_KEY, unavailableReason, "fake provider down");
    }
    if (failureMode == FailureMode.AI_UNAVAILABLE) {
      throw new AiUnavailableException("fake AI cost cap reached (" + step + ")");
    }
  }

  /** Reference per-unit pence for a key, defaulting to 100p when the key has no reference. */
  private int referenceUnitPence(String ingredientMappingKey) {
    return referencePriceSource
        .referencePrice(ingredientMappingKey)
        .map(ReferencePrice::unitPence)
        .orElse(100);
  }
}
