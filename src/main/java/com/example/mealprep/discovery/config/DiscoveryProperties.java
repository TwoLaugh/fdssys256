package com.example.mealprep.discovery.config;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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
 * <p>{@link #parallelSources} — opt-in cross-source parallelism for the runner's search phase
 * (default {@code false}). When {@code false} the search phase iterates requested sources
 * sequentially on the single runner thread, exactly as v1 ships (see {@code lld/discovery.md}
 * §Concurrency). When {@code true} each source's {@code search(...)} is fanned out onto a bounded
 * pool and the merged results are deterministically re-ordered + capped after join. {@link
 * #parallelSourceTimeout} bounds each per-source search future when the flag is on (default 30s).
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
    @NotNull Duration sitemapCacheTtl,
    @NotNull BigDecimal candidateFilterMinConfidence,
    boolean parallelSources,
    @NotNull Duration parallelSourceTimeout) {

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
    if (candidateFilterMinConfidence == null) {
      candidateFilterMinConfidence = new BigDecimal("0.6");
    }
    // parallelSources is a primitive boolean → defaults to false when unbound (v1 default-off).
    if (parallelSourceTimeout == null) {
      parallelSourceTimeout = Duration.ofSeconds(30);
    }
  }
}
