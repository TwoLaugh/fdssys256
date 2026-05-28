package com.example.mealprep.grocery.domain.service.internal.providers;

/**
 * Tier-3 grocery-provider SPI (grocery-01e). Per lld/grocery.md lines 645-674. The interface is
 * module-public (re-exported via {@code GroceryModule} for cross-module testability and future
 * multi-provider plug-in); concrete impls are package-private and live ONLY in this {@code
 * domain.service.internal.providers} pocket (the {@code GroceryBoundaryTest} provider-pocket rule).
 *
 * <p>Three structural rules (lld/grocery.md lines 668-672):
 *
 * <ol>
 *   <li>{@link #placeOrder} drives a basket up to checkout and STOPS — it never confirms.
 *       Confirmation happens in the provider's UI by the user; the returned {@code confirmLink} is
 *       the URL they click.
 *   <li>Persistent state (cookies, session cursor) round-trips through {@code
 *       grocery_provider_state} on every call — the implementation reads state in, calls the
 *       provider, writes updated state out. Provider memory is transient.
 *   <li>{@link ProviderUnavailableException} and {@link ProviderPartialFailureException} are
 *       CHECKED — the compiler forces the calling service to catch + surface them (never blindly
 *       retried).
 * </ol>
 *
 * <p>v1 ships ONLY the deterministic {@code FakeGroceryProvider} (test-scoped, in this pocket).
 * Real Tesco browser automation ({@code TescoGroceryProvider}) is a DEFERRED post-v1 ticket.
 */
public interface GroceryProvider {

  /** Stable provider identity (e.g. {@code "fake"}, {@code "tesco"}). */
  String providerKey();

  /** Price the basket. Throws when the provider is unavailable / the session expired. */
  QuoteResult quote(BasketDraft draft) throws ProviderUnavailableException;

  /**
   * Drive the basket to checkout (NEVER confirms). Throws {@link ProviderPartialFailureException}
   * when only a subset of lines could be added (a fail-forward outcome the service persists as
   * {@code PLACED_PARTIAL}); throws {@link ProviderUnavailableException} when the provider is down.
   */
  PlaceOrderResult placeOrder(BasketDraft draft)
      throws ProviderUnavailableException, ProviderPartialFailureException;

  /** Poll the provider's current status for an order. */
  OrderStatus checkStatus(String providerOrderId) throws ProviderUnavailableException;

  /** Cancel the provider-side order. */
  void cancel(String providerOrderId) throws ProviderUnavailableException;
}
