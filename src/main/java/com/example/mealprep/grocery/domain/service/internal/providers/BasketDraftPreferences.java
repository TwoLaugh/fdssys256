package com.example.mealprep.grocery.domain.service.internal.providers;

/**
 * Quality / fulfilment preferences carried on a {@link BasketDraft}. Per lld/grocery.md line 662 —
 * derived from the lifestyle config's {@code groceryQualityPreferences}. The provider reads these
 * when resolving SKUs; the fake ignores them.
 */
public record BasketDraftPreferences(
    boolean preferOwnBrand,
    boolean preferOrganic,
    boolean preferFreeRange,
    String deliverySlotPreference) {}
