package com.example.mealprep.discovery.domain.entity;

/**
 * The strategy a {@code DiscoverySource} uses to enumerate candidate URLs. Values mirror the locked
 * 2026-05-07 LLD DB-section list ({@code lld/discovery.md} lines 76-77); the older entity-section
 * list ({@code SEARCH_ENGINE} / {@code RECIPE_SITE_API}) is superseded.
 */
public enum DiscoverySourceKind {
  SITEMAP,
  RSS_FEED,
  CATEGORY_INDEX,
  SEARCH_API
}
