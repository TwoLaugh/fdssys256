package com.example.mealprep.discovery.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the discovery module — bound to {@code mealprep.discovery.*}. Per
 * ticket invariant 41.
 *
 * <p>{@link #heartbeatTimeout} — orphan sweep cutoff in 01d ({@code RUNNING} jobs older than this
 * are finalised as {@code FAILED}). {@link #duplicateLookbackDays} — fingerprint-dedup lookback in
 * 01d. {@link #syncTimeout} — {@code runJobSync} hard cap in 01f. {@link #robotsCacheTtl} —
 * per-host robots.txt cache TTL in 01c. {@link #sitemapCacheTtl} — per-instance sitemap cache TTL
 * for curated {@code SITEMAP} sources in 01e (default 6h; long-running runner instances refresh
 * rather than caching once-per-jvm).
 *
 * <p>Spring Boot 3.x supports record-shaped {@code @ConfigurationProperties}; defaults assigned in
 * the canonical constructor below to keep the bean usable when no overrides are configured.
 */
@ConfigurationProperties(prefix = "mealprep.discovery")
@Validated
public record DiscoveryProperties(
    @NotNull Duration heartbeatTimeout,
    int duplicateLookbackDays,
    @NotNull Duration syncTimeout,
    @NotNull Duration robotsCacheTtl,
    @NotNull Duration sitemapCacheTtl) {

  public DiscoveryProperties {
    if (heartbeatTimeout == null) {
      heartbeatTimeout = Duration.ofMinutes(10);
    }
    if (duplicateLookbackDays <= 0) {
      duplicateLookbackDays = 30;
    }
    if (syncTimeout == null) {
      syncTimeout = Duration.ofSeconds(60);
    }
    if (robotsCacheTtl == null) {
      robotsCacheTtl = Duration.ofHours(1);
    }
    if (sitemapCacheTtl == null) {
      sitemapCacheTtl = Duration.ofHours(6);
    }
  }
}
