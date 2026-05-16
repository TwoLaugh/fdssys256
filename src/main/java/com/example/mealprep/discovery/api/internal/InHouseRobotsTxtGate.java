package com.example.mealprep.discovery.api.internal;

import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.example.mealprep.discovery.domain.service.RobotsTxtGate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Minimal in-house robots.txt parser + gate. Lives in {@code discovery.api.internal} (NOT {@code
 * domain.service.internal}) because it depends on Spring Web ({@code RestClient}) — the {@code
 * springWebStaysInApi} rule forbids that import elsewhere.
 *
 * <p>v1 design choice: ship a tiny in-house parser rather than add a {@code crawler-commons}
 * dependency (which would be a separate {@code chore:} ticket per the playbook). Covers {@code
 * User-agent} + {@code Disallow} with prefix match. {@code Allow:} precedence and wildcard paths
 * are NOT supported — if production traffic surfaces those, a future {@code chore:} ticket can swap
 * to crawler-commons via {@link ConditionalOnMissingBean}.
 *
 * <p>Per-host cache keyed by hostname with {@link DiscoveryProperties#robotsCacheTtl} TTL.
 * Thundering-herd on miss is acceptable (per-host cost is one extra HTTP GET).
 */
@Component
@ConditionalOnMissingBean(RobotsTxtGate.class)
public class InHouseRobotsTxtGate implements RobotsTxtGate {

  private static final Logger log = LoggerFactory.getLogger(InHouseRobotsTxtGate.class);
  private static final String DEFAULT_URI_TEMPLATE = "https://{host}/robots.txt";

  private final RestClient robotsRestClient;
  private final DiscoveryProperties properties;
  private final Clock clock;
  private final String uriTemplate;
  private final Map<String, CachedRobotsTxt> cache = new ConcurrentHashMap<>();

  public InHouseRobotsTxtGate(
      RestClient robotsRestClient, DiscoveryProperties properties, Clock clock) {
    this(robotsRestClient, properties, clock, DEFAULT_URI_TEMPLATE);
  }

  /** Test-only constructor — lets ITs aim the gate at a WireMock-served {@code http://} origin. */
  InHouseRobotsTxtGate(
      RestClient robotsRestClient,
      DiscoveryProperties properties,
      Clock clock,
      String uriTemplate) {
    this.robotsRestClient = robotsRestClient;
    this.properties = properties;
    this.clock = clock;
    this.uriTemplate = uriTemplate;
  }

  @Override
  public RobotsTxtOutcome check(URI candidateUrl, String userAgent) {
    String host = candidateUrl.getHost();
    if (host == null) {
      return RobotsTxtOutcome.UNAVAILABLE;
    }
    Instant now = Instant.now(clock);
    CachedRobotsTxt cached = cache.get(host);
    if (cached == null
        || Duration.between(cached.fetchedAt(), now).compareTo(properties.robotsCacheTtl()) > 0) {
      cached = fetch(host, userAgent, now);
      cache.put(host, cached);
    }
    return cached.outcome(candidateUrl.getPath(), userAgent);
  }

  private CachedRobotsTxt fetch(String host, String userAgent, Instant now) {
    try {
      String body =
          robotsRestClient
              .get()
              .uri(uriTemplate, host)
              .header(HttpHeaders.USER_AGENT, userAgent)
              .retrieve()
              .body(String.class);
      return CachedRobotsTxt.parse(body, now);
    } catch (HttpClientErrorException.NotFound e) {
      // 404 → no policy → ALLOWED by default (RFC 9309 §2.4).
      return CachedRobotsTxt.allowAll(now);
    } catch (RestClientException e) {
      log.warn("robots.txt fetch failed for host={} cause={}", host, e.toString());
      return CachedRobotsTxt.unavailable(now);
    }
  }

  /**
   * Parsed robots.txt entries. {@code disallowsByUa} keys are lowercased UA tokens; values are the
   * {@code Disallow:} path prefixes for that UA. {@code *} is the wildcard UA fallback.
   */
  record CachedRobotsTxt(
      Map<String, List<String>> disallowsByUa, boolean unavailable, Instant fetchedAt) {

    static CachedRobotsTxt allowAll(Instant now) {
      return new CachedRobotsTxt(Map.of(), false, now);
    }

    static CachedRobotsTxt unavailable(Instant now) {
      return new CachedRobotsTxt(Map.of(), true, now);
    }

    static CachedRobotsTxt parse(String body, Instant now) {
      if (body == null || body.isBlank()) {
        return allowAll(now);
      }
      Map<String, List<String>> result = new HashMap<>();
      List<String> currentUas = new ArrayList<>();
      boolean sawDirectiveSinceUaBlock = false;
      for (String rawLine : body.split("\\R")) {
        String line = stripComment(rawLine).trim();
        if (line.isEmpty()) {
          continue;
        }
        int colon = line.indexOf(':');
        if (colon < 0) {
          continue;
        }
        String key = line.substring(0, colon).trim().toLowerCase();
        String value = line.substring(colon + 1).trim();
        if ("user-agent".equals(key)) {
          // Adjacent User-agent lines (no Disallow between them) share the same record.
          if (sawDirectiveSinceUaBlock) {
            currentUas = new ArrayList<>();
            sawDirectiveSinceUaBlock = false;
          }
          currentUas.add(value.toLowerCase());
          result.computeIfAbsent(value.toLowerCase(), k -> new ArrayList<>());
        } else if ("disallow".equals(key) && !currentUas.isEmpty()) {
          sawDirectiveSinceUaBlock = true;
          if (!value.isEmpty()) {
            for (String ua : currentUas) {
              result.get(ua).add(value);
            }
          }
        }
      }
      return new CachedRobotsTxt(Map.copyOf(result), false, now);
    }

    RobotsTxtOutcome outcome(String path, String userAgent) {
      if (unavailable) {
        return RobotsTxtOutcome.UNAVAILABLE;
      }
      String normalisedPath = path == null || path.isEmpty() ? "/" : path;
      String uaLower = userAgent == null ? "*" : userAgent.toLowerCase();
      List<String> specific = matchUa(uaLower);
      List<String> wildcard = disallowsByUa.getOrDefault("*", List.of());
      List<String> applicable = specific != null ? specific : wildcard;
      for (String rule : applicable) {
        if (normalisedPath.startsWith(rule)) {
          return RobotsTxtOutcome.DISALLOWED;
        }
      }
      return RobotsTxtOutcome.ALLOWED;
    }

    private List<String> matchUa(String uaLower) {
      // Longest-token match on UA substring (mirrors the common robots.txt convention).
      List<String> bestMatch = null;
      int bestLength = -1;
      for (Map.Entry<String, List<String>> entry : disallowsByUa.entrySet()) {
        String ua = entry.getKey();
        if ("*".equals(ua)) {
          continue;
        }
        if (uaLower.contains(ua) && ua.length() > bestLength) {
          bestMatch = entry.getValue();
          bestLength = ua.length();
        }
      }
      return bestMatch;
    }

    private static String stripComment(String line) {
      int hash = line.indexOf('#');
      return hash < 0 ? line : line.substring(0, hash);
    }
  }
}
