package com.example.mealprep.discovery.domain.entity;

/**
 * Two-bucket classification of a discovery source: hand-curated recipe sites vs. broad search-API
 * sources. Per locked 2026-05-07 LLD line 76.
 */
public enum DiscoverySourceType {
  CURATED,
  SEARCH
}
