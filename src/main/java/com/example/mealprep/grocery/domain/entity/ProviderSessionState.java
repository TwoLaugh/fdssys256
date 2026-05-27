package com.example.mealprep.grocery.domain.entity;

import java.util.Map;

/**
 * Provider session blob persisted in {@link GroceryProviderState}'s {@code sessionState} JSONB
 * column. Per lld/grocery.md line 187-188 / 378. Cookies + a navigation cursor + per-provider
 * opaque extras — read whole, no inner fields the system filters on. NO card / payment data.
 *
 * <p><b>Worth user review</b> — {@code providerExtras} is {@code Map<String, Object>}; the style
 * guide warns against {@code Object}-typed JSONB. Acceptable here because each provider carries its
 * own opaque typed extras and v1 has no real provider; flag for the deferred Tesco ticket to pin a
 * typed shape.
 */
public record ProviderSessionState(
    Map<String, String> cookies, String navigationCursor, Map<String, Object> providerExtras) {}
