# Ticket: discovery — 01c `DiscoverySource` SPI + Source Registry + RobotsTxtGate + RateLimiterRegistry + ContentFingerprintHasher + CandidateAiFilter (no-op)

## Summary

Layer the **per-source plugin SPI + politeness/dedup helpers** on top of 01a/01b. Per [`lld/discovery.md`](../../lld/discovery.md) §`DiscoverySource` (the SPI lines 370-398), §Internal helper interfaces (lines 404-418), §Source-internal types (lines 274-296), §Rate limiting / circuit breaker (lines 564-565), §Failure modes (politeness lines 579-580). Ships:

- **`DiscoverySource` public SPI** in `discovery/domain/service/DiscoverySource.java` — the 2-stage `search(DiscoveryQuery): List<DiscoveryCandidate>` + `fetchRecipe(DiscoveryCandidate): ParsedRecipe` + `key()` + `kind()` + `robotsTxtUri()` interface. **Zero concrete impls in 01c** — `source/` package ships empty per [LLD line 52](../../lld/discovery.md). 01e ships the first concrete impls (curated reference impl + Google CSE adapter).
- **`SourceRegistry`** in `discovery/domain/service/internal/SourceRegistry.java` — package-private `@Component` that injects `List<DiscoverySource>` (Spring auto-wires all `@Component`-annotated implementations) plus `DiscoverySourceRepository`. Indexes by `sourceKey`; provides `Optional<DiscoverySource> bySourceKey(String key)`, `List<DiscoverySource> resolveEnabled(List<String> requestedKeys)`. **Re-reads the DB once per job** (LLD line 193 "the runner reads via `findAllEnabled` once per job and never mid-job — source toggles take effect on the next job") — but the registry caches the bean list at startup; only the per-job DB read varies.
- **`RobotsTxtGate`** — public interface (LLD line 405) + a single `CrawlerCommonsRobotsTxtGate` impl in `discovery/api/internal/` (location matters — see Gotcha block on HTTP-client adapter location). Wraps `crawler-commons` library OR a minimal in-house impl (see decision below). Per-host cache with TTL from `DiscoveryProperties.robotsCacheTtl` (default 1h).
- **`RateLimiterRegistry`** — package-private `@Component` in `discovery/domain/service/internal/`. Holds `Map<String, RateLimiter>` keyed by `sourceKey`, configured from `DiscoverySource.requestsPerMinute`. Uses Resilience4j (already in the project's deps; verify before adding to pom). Exposes `boolean tryAcquire(String sourceKey)` returning false on starvation (the runner converts to a `RATE_LIMITED` scrape row).
- **`ContentFingerprintHasher`** — package-private `@Component`. Computes SHA-256 over the normalised body per [LLD line 529](../../lld/discovery.md): "sorted ingredient mapping keys + concatenated method instructions, lowercased, whitespace-collapsed". Pure function; no state.
- **`CandidateAiFilter`** — public interface + a single `PassThroughCandidateAiFilter` `@Component` impl per [LLD lines 412-421](../../lld/discovery.md). v1 returns the input unchanged + logs a warning. The interface lives in `domain.service.internal` (package-private) per LLD line 412 — but the LLD calls it a "Spring bean" so it's a `@Component`-annotated class implementing an interface. Future replacement (prompt + AI integration) is one `@ConditionalOnMissingBean` away.
- **`DiscoveryQuery`, `DiscoveryCandidate`, `ParsedRecipe`** records under `discovery/api/dto/` — needed by the SPI signatures. Per [LLD lines 274-296](../../lld/discovery.md) verbatim.
- **`DiscoverySourceUnavailableException` + `ExtractionFailedException`** — internal signals (LLD line 458) thrown by source impls; caught by the runner in 01d and converted to scrape rows. **Never reach the global exception handler.** Both extend `DiscoveryException` from 01a but are NOT mapped in `DiscoveryExceptionHandler` from 01b. They're caught by the runner.
- **ArchUnit rule** in `DiscoveryBoundaryTest`: `DiscoverySource` SPI is implemented only inside `discovery.source.*` (LLD line 619). 01c's `source/` package is empty so the rule is vacuously true today; lands so 01e+ can't slip impls into the wrong package.

**Defers** (still out of scope after 01c):
- The actual async runner — `DiscoveryJobRunner` per-step transactions, claim/search/AI-filter/fetch/persist flow, hard-constraint filter call, RecipeWriteApi.saveImportedRecipe handoff, DiscoveryRecipeIngestedEvent, DiscoveryJobCompletedEvent, orphan sweep impl, in-memory cancellation flag → **discovery-01d**
- Curated source seed + concrete `DiscoverySource` impls (one reference curated + Google CSE adapter) → **discovery-01e**
- Sync admin endpoint + `runJobSync` + `CompletableFuture` coordination + 408/502 mappings → **discovery-01f**

## Behavioural spec

### `DiscoverySource` SPI

1. New **public** interface `com.example.mealprep.discovery.domain.service.DiscoverySource` per [LLD lines 375-397](../../lld/discovery.md) verbatim:
   - `String key()` — stable identifier matching `discovery_sources.source_key`.
   - `DiscoverySourceKind kind()` — enum from 01a.
   - `List<DiscoveryCandidate> search(DiscoveryQuery query)` — cheap, no page fetches. Honours `query.maxResults()`. Throws `DiscoverySourceUnavailableException` on permanent source-level failure.
   - `ParsedRecipe fetchRecipe(DiscoveryCandidate candidate)` — full-page fetch + extraction. Strategy choice is the source's concern. Throws `ExtractionFailedException` on inability to produce a coherent recipe.
   - `default Optional<URI> robotsTxtUri()` — empty for sources with their own API.
2. The interface is **public** (cross-module-grep-able) so future impls can sit anywhere — but the **ArchUnit rule** in invariant 27 restricts impls to `discovery.source.*`. Per LLD line 400 the source package is a "hard pocket" — empty in v1.

### `DiscoveryQuery`, `DiscoveryCandidate`, `ParsedRecipe` records

3. `DiscoveryQuery(DiscoveryConstraints constraints, int maxResults, String userAgent)` per [LLD line 275](../../lld/discovery.md) verbatim. Java record, public, in `discovery/api/dto/`.
4. `DiscoveryCandidate(String sourceKey, String candidateUrl, String snippetTitle, String snippetDescription, Map<String, String> sourceMetadata)` per LLD line 277. **`Map<String, String>` shape is flat** — SERP rank, RSS pub date, etc. The runner doesn't read inner fields; it passes the whole `DiscoveryCandidate` back to `fetchRecipe`. The Map is for the source's own use across the search → fetch boundary.
5. `ParsedRecipe(String canonicalUrl, String name, String description, List<ParsedIngredient> ingredients, List<ParsedMethodStep> method, ParsedRecipeMetadata metadata, String extractionMethod, BigDecimal extractionConfidence)` per LLD line 283. Plus the three nested records `ParsedIngredient`, `ParsedMethodStep`, `ParsedRecipeMetadata` per LLD lines 291-295.
6. **`ParsedRecipe` is field-compatible with the recipe module's `CreateRecipeRequest`** per LLD line 297 — but **discovery's `ParsedRecipe` is NOT the same class as the recipe module's `ParsedRecipe`** (recipe-01b ships `HtmlImportParser.ParsedRecipe` as a nested record; the recipe-extraction-pipeline LLD specifies a more elaborate shape). Discovery's record lives in `com.example.mealprep.discovery.api.dto` — separate package, separate class. The runner maps `ParsedRecipe` → `RecipeWriteApi.saveImportedRecipe` input shape in 01d, **NOT** in 01c. **LLD divergence noted**: the LLD frames this as a shared shape; the implementation reality is two separately-evolving record types that the runner translates between.
7. **Nutrition fields are absent** from `ParsedRecipe` per LLD line 299. Per HLD: "external nutrition data is DISCARDED — recalculated internally". The recipe module's nutrition pipeline back-fills.

### `SourceRegistry`

8. Package-private `@Component` `SourceRegistry` in `discovery/domain/service/internal/`. Constructor: `List<DiscoverySource> sources, DiscoverySourceRepository repository`.
9. **Startup index**: `Map<String, DiscoverySource> sourcesByKey` built in `@PostConstruct`. Logs INFO `"discovery source registry: {} bean(s) registered: {}"` with the key list. **Tolerates zero beans** in 01c (the `source/` package is empty); future tickets add beans.
10. `Optional<DiscoverySource> bySourceKey(String key)` — Map lookup.
11. `List<DiscoverySource> resolveEnabled()` — reads `discoverySourceRepository.findByEnabledTrue()` then filters to keys present in the bean map. **Sources with a DB row but no bean** are LOGGED at WARN and skipped (they'd otherwise be silently ignored, masking deploy errors).
12. `List<DiscoverySource> resolveEnabledByKey(Collection<String> requestedKeys)` — same as above but additionally filters to the requested set. Used by `startJob`'s subset path.
13. **Read-once-per-job discipline**: 01c provides the lookup methods; 01d calls them once at the top of `DiscoveryJobRunner.run` (per LLD line 193 "never mid-job"). The registry itself does NOT cache the DB read — the runner is responsible for calling it once and pinning the result for the job duration.

### `RobotsTxtGate`

14. New **public** interface `com.example.mealprep.discovery.domain.service.RobotsTxtGate` per [LLD lines 405-408](../../lld/discovery.md):
    ```java
    public interface RobotsTxtGate {
      RobotsTxtOutcome check(URI candidateUrl, String userAgent);
    }
    ```
15. New impl `CrawlerCommonsRobotsTxtGate` in `discovery/api/internal/` (location matters — see Gotchas block on HTTP-client adapter location). Annotated `@Component`. **`@ConditionalOnMissingBean(RobotsTxtGate.class)`** so tests can override with a stub.
16. **Library choice** per [LLD line 631](../../lld/discovery.md) ("Recommendation: Crawler-Commons — not locked"):
    - **Option A**: Crawler-Commons (Java; mature; longest-match `User-agent`/`Allow`/`Disallow` semantics). **Adds 1 dependency** to `pom.xml`. Per [LLD line 631](../../lld/discovery.md), this is the recommendation.
    - **Option B**: Minimal in-house parser — fetch `<base>/robots.txt`, split on lines, parse `User-agent: *` and `Disallow: <path>` entries, do prefix-match.
    - **Decision**: ship Option B in 01c (minimal in-house parser). Adding a new pom dependency is a separate `chore:` ticket per [playbook line 489](../../lld/implementation-playbook.md) ("Top-level pom.xml changes are their own `chore:` tickets"). The in-house parser handles the common case; if production traffic surfaces edge cases (`Allow:` precedence, wildcard paths), swap to Crawler-Commons via a future `chore:` ticket. **Worth user review.**
17. **Per-host cache**: `Map<String, CachedRobotsTxt>` keyed by hostname. Each entry holds `(rules, fetchedAt)`. TTL is `DiscoveryProperties.robotsCacheTtl` (default 1h). Concurrent map (`ConcurrentHashMap`); single-flight on cache miss is not required for v1 (a thundering herd briefly double-fetches; cheap).
18. **HTTP fetch** via `RestClient` (Spring 6.1) — `@Bean RestClient robotsRestClient()` in `discovery/config/DiscoveryHttpConfig.java`. Timeout 5s. Follow redirects (max 3). User-Agent from the source's `user_agent` field.
19. **Outcomes** (LLD lines 156, 525-526, 579):
    - `ALLOWED` — `robots.txt` returned 200 and the URL path is not in any `Disallow:` entry for the source's UA.
    - `DISALLOWED` — 200 + Disallow matches.
    - `UNAVAILABLE` — 5xx, timeout, or connection refused. Per LLD line 579, this is "polite-by-default skip" — the runner treats as `SKIPPED` for sources with `respectRobotsTxt = true`.
    - `SKIPPED` — source has no `robotsTxtUri()` (API-based sources); the runner skips the check entirely.
20. **404 on `robots.txt`** → treat as `ALLOWED`. The site has no robots policy. **LLD silent on this** — invariant per RFC 9309 §2.4 "If a robots.txt is not accessible, the crawler MAY assume there's no specific restriction."

### `RateLimiterRegistry`

21. Package-private `@Component` `RateLimiterRegistry` in `discovery/domain/service/internal/`. Constructor: `DiscoverySourceRepository repository`.
22. **Lazy per-source initialisation**: `Map<String, RateLimiter> limiters` populated on first use of a source key. Configuration drawn from `DiscoverySource.requestsPerMinute`. Resilience4j `RateLimiter.of(name, RateLimiterConfig.custom().limitForPeriod(rpm).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build())`.
23. **`boolean tryAcquire(String sourceKey)`** — `limiter.acquirePermission()` with `timeoutDuration = ZERO`. Returns false immediately if no token; true if acquired. The runner converts false to a `RATE_LIMITED` scrape row per LLD line 522.
24. **Configuration change on next instantiation** per LLD line 564: "Configuration changes apply on next runner instantiation; in-flight tokens not redistributed." The registry's map is keyed by sourceKey; the runner does NOT mutate it mid-job.
25. **Resilience4j dependency**: verify `io.github.resilience4j:resilience4j-spring-boot3` is in the pom. If absent, FALLBACK to a tiny in-house token bucket (`Map<String, AtomicInteger> + LinkedBlockingQueue` per-source). **Worth user review** — adding Resilience4j is preferable; if missing, the agent should report and let the parent decide whether to add it (a `chore:` ticket) or fallback.

### Per-source circuit breaker

26. Per [LLD line 565](../../lld/discovery.md): "`failure_streak >= 5` in `discovery_sources` → source skipped for the next hour even if `enabled = true`. Bookkeeping updated by the runner; reset on a successful call."
27. **01c ships the circuit-breaker QUERY**: `boolean isCircuitOpen(DiscoverySource source, Instant now)` — pure function on `failureStreak >= 5 && lastFailureAt > now - 1h`. Lives on `SourceRegistry`.
28. **01c ships the bookkeeping HELPERS**: `recordSuccess(String sourceKey)` and `recordFailure(String sourceKey)` on `SourceRegistry`. Each does a `findBySourceKey + setLastSuccessAt/lastFailureAt + adjust failureStreak + save`. Both are `@Transactional` (REQUIRED).
29. **01d calls these helpers** from the runner. 01c provides the surface; the runner triggers it.

### `ContentFingerprintHasher`

30. Package-private `@Component` `ContentFingerprintHasher` in `discovery/domain/service/internal/`.
31. **`String fingerprint(ParsedRecipe recipe)`** — pure function. Algorithm per [LLD line 529](../../lld/discovery.md):
    1. Extract ingredient mapping keys: `recipe.ingredients().stream().map(ParsedIngredient::ingredientMappingKey).filter(Objects::nonNull).map(String::toLowerCase).map(String::trim).sorted().collect(Collectors.joining("\n"))`.
    2. Extract method instructions: `recipe.method().stream().map(ParsedMethodStep::instruction).map(String::toLowerCase).map(s -> s.replaceAll("\\s+", " ")).map(String::trim).collect(Collectors.joining("\n"))`.
    3. Concatenate: `String input = ingredientKeysSorted + "\n---\n" + methodInstructionsLower`.
    4. SHA-256: `MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));` → hex-encode (64 chars).
32. **Stability**: identical recipes (modulo whitespace, case, ingredient order) produce identical hashes. Tested in `ContentFingerprintHasherTest`.
33. **`existsByContentFingerprint` lookup** (LLD line 333) — the repo method is already declared in 01a. The runner calls it in 01d.
34. **Lookback window** (`DiscoveryProperties.duplicateLookbackDays`, default 30) — applied by the runner, not by the hasher. 01d's runner queries `scrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter(fingerprint, now - lookbackDays)` — a NEW repo method that 01d adds.

### `CandidateAiFilter` no-op

35. New public interface `com.example.mealprep.discovery.domain.service.internal.CandidateAiFilter` per [LLD lines 412-418](../../lld/discovery.md). **Package-private internal interface** per the LLD ("Internal helper interfaces" header) — but it lives in `domain.service.internal`. Other modules don't inject it; only the runner does.
36. New impl `PassThroughCandidateAiFilter` `@Component` in same package. `@ConditionalOnMissingBean(CandidateAiFilter.class)` so a future real impl supersedes via the SPI-with-Noop pattern.
    - **CRITICAL**: per the [SPI-with-Noop gotcha](../../../ai-workflow/templates/agent-prompt-template.md), do NOT use `@Component @ConditionalOnMissingBean` on the class itself. Use a `@Configuration` class with a `@Bean` method whose method name differs from the configuration class name:
      ```java
      @Configuration
      public class NoopCandidateAiFilterConfiguration {
        @Bean
        @ConditionalOnMissingBean(CandidateAiFilter.class)
        CandidateAiFilter defaultCandidateAiFilter() {
          return new PassThroughCandidateAiFilter();
        }
        static class PassThroughCandidateAiFilter implements CandidateAiFilter { ... }
      }
      ```
    Class name `NoopCandidateAiFilterConfiguration` (bean `noopCandidateAiFilterConfiguration`); method name `defaultCandidateAiFilter` (bean `defaultCandidateAiFilter`) — DIFFERENT bean names. The round-5 bug-2 fix is encoded here.
37. **Behaviour**: `filter(candidates, constraints, userId) -> candidates` — returns the input unchanged. Logs WARN `"CandidateAiFilter pass-through: returning {} candidates unfiltered (v1)"` on every call. The warn-on-every-call is intentional per LLD line 421: "When the prompt and integration land, the bean is replaced — no caller change."

### Internal exceptions

38. New `DiscoverySourceUnavailableException extends DiscoveryException` in `discovery/exception/`. Constructor `(String sourceKey, String reason, Throwable cause)`. **Not** mapped in `DiscoveryExceptionHandler` from 01b — caught by the runner in 01d.
39. New `ExtractionFailedException extends DiscoveryException` in `discovery/exception/`. Constructor `(String candidateUrl, String reason)`. **Not** mapped in the exception handler.

### ArchUnit rule

40. Append a rule to `DiscoveryBoundaryTest` (existing from 01a):
    - **`DiscoverySource` implementations live only in `discovery.source..`**. Today the rule is vacuously true (source package is empty); 01e adds impls so the rule kicks in.
    ```java
    @ArchTest
    static final ArchRule discoverySourceImplsLiveInSourcePackage =
        classes().that().implement(DiscoverySource.class)
            .should().resideInAPackage("com.example.mealprep.discovery.source..");
    ```
41. **Do NOT add the "DiscoveryService injected only by planner.* and recipe.*" rule** — defer to 01d when the runner's call paths stabilise and planner-01d/recipe-01d (or wherever the consumers land) ship.

## Database

**Zero migrations in 01c.** All schema landed in 01a.

## OpenAPI updates

**Zero OpenAPI changes in 01c.** No new endpoints; the SPI is internal.

## Verbatim shape snippets

### `DiscoverySource` SPI

```java
public interface DiscoverySource {
  /** Stable key matching discovery_sources.source_key. */
  String key();

  DiscoverySourceKind kind();

  /**
   * Produce candidate URLs for the constraint set. Cheap — does not fetch full pages.
   * Implementations honour maxResults to bound per-source dominance.
   * @throws DiscoverySourceUnavailableException on permanent source-level failure.
   */
  List<DiscoveryCandidate> search(DiscoveryQuery query);

  /**
   * Fetch the full page and produce a structured ParsedRecipe. The runner invokes only
   * after robots.txt and rate-limit checks pass. The HTML extraction strategy
   * (microdata / JSON-LD / site templates / AI fallback) is the source's concern.
   * @throws ExtractionFailedException when extraction cannot produce a coherent recipe.
   */
  ParsedRecipe fetchRecipe(DiscoveryCandidate candidate);

  /** Robots.txt URI (empty for sources with their own API — runner skips robots check). */
  default Optional<URI> robotsTxtUri() {
    return Optional.empty();
  }
}
```

### `ParsedRecipe` shape

```java
public record ParsedRecipe(
    String canonicalUrl, String name, String description,
    List<ParsedIngredient> ingredients,
    List<ParsedMethodStep> method,
    ParsedRecipeMetadata metadata,
    String extractionMethod,
    BigDecimal extractionConfidence
) {
  public record ParsedIngredient(
      String displayName, String ingredientMappingKey,
      BigDecimal quantity, String unit, String preparation, boolean optional) {}

  public record ParsedMethodStep(int stepNumber, String instruction, Integer durationMinutes) {}

  public record ParsedRecipeMetadata(
      Integer servings, Integer prepTimeMins, Integer cookTimeMins, Integer totalTimeMins,
      List<String> equipmentRequired, String cuisine, List<String> mealTypes) {}
}
```

### `RobotsTxtGate` (in-house minimal impl)

```java
public interface RobotsTxtGate {
  RobotsTxtOutcome check(URI candidateUrl, String userAgent);
}

@Component
@ConditionalOnMissingBean(RobotsTxtGate.class)
@RequiredArgsConstructor
public class InHouseRobotsTxtGate implements RobotsTxtGate {
  private static final Logger log = LoggerFactory.getLogger(InHouseRobotsTxtGate.class);
  private final RestClient robotsRestClient;
  private final DiscoveryProperties properties;
  private final Clock clock;
  private final Map<String, CachedRobotsTxt> cache = new ConcurrentHashMap<>();

  @Override
  public RobotsTxtOutcome check(URI candidateUrl, String userAgent) {
    String host = candidateUrl.getHost();
    if (host == null) return RobotsTxtOutcome.UNAVAILABLE;
    CachedRobotsTxt cached = cache.get(host);
    if (cached == null || Duration.between(cached.fetchedAt, Instant.now(clock))
        .compareTo(properties.robotsCacheTtl()) > 0) {
      cached = fetch(host, userAgent);
      cache.put(host, cached);
    }
    return cached.outcome(candidateUrl.getPath(), userAgent);
  }

  private CachedRobotsTxt fetch(String host, String userAgent) {
    try {
      String body = robotsRestClient.get()
          .uri("https://{host}/robots.txt", host)
          .header(HttpHeaders.USER_AGENT, userAgent)
          .retrieve()
          .body(String.class);
      return CachedRobotsTxt.parse(body, Instant.now(clock));
    } catch (HttpClientErrorException.NotFound e) {
      // 404 → no policy → ALLOWED by default
      return CachedRobotsTxt.allowAll(Instant.now(clock));
    } catch (RestClientException e) {
      log.warn("robots.txt fetch failed for host={} cause={}", host, e.toString());
      return CachedRobotsTxt.unavailable(Instant.now(clock));
    }
  }

  // CachedRobotsTxt parses lines, holds Map<String, List<String>> disallowsByUa, plus outcome(path, ua) impl
}
```

### `SourceRegistry`

```java
@Component
@RequiredArgsConstructor
class SourceRegistry {
  private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);
  private final List<DiscoverySource> sources;
  private final DiscoverySourceRepository repository;
  private Map<String, DiscoverySource> byKey;

  @PostConstruct
  void index() {
    byKey = sources.stream().collect(Collectors.toUnmodifiableMap(
        DiscoverySource::key, Function.identity()));
    log.info("discovery source registry: {} bean(s) registered: {}", byKey.size(), byKey.keySet());
  }

  Optional<DiscoverySource> bySourceKey(String key) {
    return Optional.ofNullable(byKey.get(key));
  }

  List<DiscoverySource> resolveEnabled() {
    return repository.findByEnabledTrue().stream()
        .map(row -> {
          DiscoverySource bean = byKey.get(row.getSourceKey());
          if (bean == null) {
            log.warn("source row '{}' enabled but no @Component bean registered — skipping", row.getSourceKey());
          }
          return bean;
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  boolean isCircuitOpen(DiscoverySource source, Instant now) {
    DiscoverySource row = repository.findBySourceKey(source.key()).orElseThrow();
    return row.getFailureStreak() >= 5
        && row.getLastFailureAt() != null
        && Duration.between(row.getLastFailureAt(), now).compareTo(Duration.ofHours(1)) < 0;
  }

  @Transactional
  void recordSuccess(String sourceKey) {
    repository.findBySourceKey(sourceKey).ifPresent(row -> {
      row.setLastSuccessAt(Instant.now());
      row.setFailureStreak(0);
      repository.save(row);
    });
  }

  @Transactional
  void recordFailure(String sourceKey) {
    repository.findBySourceKey(sourceKey).ifPresent(row -> {
      row.setLastFailureAt(Instant.now());
      row.setFailureStreak(row.getFailureStreak() + 1);
      repository.save(row);
    });
  }
}
```

### `CandidateAiFilter` Noop config

```java
@Configuration
public class NoopCandidateAiFilterConfiguration {

  @Bean
  @ConditionalOnMissingBean(CandidateAiFilter.class)
  CandidateAiFilter defaultCandidateAiFilter() {
    return new PassThroughCandidateAiFilter();
  }

  static class PassThroughCandidateAiFilter implements CandidateAiFilter {
    private static final Logger log = LoggerFactory.getLogger(PassThroughCandidateAiFilter.class);
    @Override
    public List<DiscoveryCandidate> filter(
        List<DiscoveryCandidate> candidates,
        DiscoveryConstraints constraints,
        UUID userId) {
      log.warn("CandidateAiFilter pass-through: returning {} candidates unfiltered (v1)", candidates.size());
      return candidates;
    }
  }
}
```

### `DiscoveryHttpConfig`

```java
@Configuration
public class DiscoveryHttpConfig {
  @Bean
  RestClient robotsRestClient() {
    return RestClient.builder()
        .requestFactory(new SimpleClientHttpRequestFactory() {{
          setConnectTimeout(5_000);
          setReadTimeout(5_000);
        }})
        .build();
  }
}
```

This bean lives in `discovery/config/` per the **HTTP-client-adapter location rule** (see Gotchas) — NOT in `domain.service.internal`.

## Edge-case checklist

### `SourceRegistry`

- [ ] Empty bean list (01c default) → `resolveEnabled()` returns empty list; INFO log notes 0 beans
- [ ] DB row exists for `src_a`, `enabled = true`, but no `@Component` impl registers → WARN log; row skipped
- [ ] DB row exists, bean exists, `enabled = false` → row NOT included in `resolveEnabled()` (only `findByEnabledTrue()` rows queried)
- [ ] `resolveEnabledByKey(["src_a"])` returns `src_a` if enabled + bean present; empty if either missing
- [ ] `isCircuitOpen` returns `true` when `failureStreak >= 5` AND `lastFailureAt > now - 1h`; `false` otherwise
- [ ] `recordSuccess` resets `failureStreak = 0`
- [ ] `recordFailure` increments `failureStreak` by 1

### `RobotsTxtGate`

- [ ] `robots.txt` returns 200 with `User-agent: *\nDisallow: /admin\n` and URL path `/admin/...` → `DISALLOWED`
- [ ] Same, URL path `/recipes/...` → `ALLOWED`
- [ ] `robots.txt` returns 404 → `ALLOWED` (per RFC 9309 §2.4)
- [ ] `robots.txt` times out → `UNAVAILABLE`; warn logged
- [ ] Same host queried twice within `robotsCacheTtl` → second call HITS cache (no second HTTP request) — verify via WireMock request count
- [ ] Same host after TTL expiry → re-fetches
- [ ] WireMock-backed `RobotsTxtGateIT` with three fixtures (200/allowed, 200/disallowed, 404, timeout) covers all four outcomes

### `RateLimiterRegistry`

- [ ] `tryAcquire("src_a")` first call → true (within budget)
- [ ] After exhausting the rpm budget → `tryAcquire` returns false within the same minute
- [ ] Different source key has independent budget
- [ ] Rapid successive calls don't deadlock (timeoutDuration = ZERO)

### `ContentFingerprintHasher`

- [ ] Two `ParsedRecipe`s with identical ingredient mapping keys + method instructions modulo whitespace + case → identical fingerprint
- [ ] Reordering ingredients in the input → identical fingerprint (sorted before hash)
- [ ] Different method text → different fingerprint
- [ ] Fingerprint is exactly 64 hex chars (SHA-256 width)
- [ ] Null `ingredientMappingKey` entries are filtered out of the hash input (don't crash, don't contribute to the hash)

### `CandidateAiFilter`

- [ ] `PassThroughCandidateAiFilter.filter(candidates, constraints, userId)` returns the input list unchanged (referential equality is acceptable; deep equality strictly required)
- [ ] WARN log fires on every call
- [ ] A test-side `@Bean CandidateAiFilter` overrides the Noop via `@ConditionalOnMissingBean` (verify in `CandidateAiFilterOverrideTest`)

### ArchUnit

- [ ] `DiscoveryBoundaryTest`'s new rule passes (vacuously today; will trip if a future impl is placed outside `discovery.source..`)

### Cross-cutting

- [ ] `CrawlerCommonsRobotsTxtGate` / `InHouseRobotsTxtGate` lives in `discovery/api/internal/` or `discovery/config/` per the HTTP-client-adapter location rule — NOT `domain/service/internal`. Verify via ArchUnit-style grep or explicit `springWebStaysInApi` rule (project-wide rule from `ModuleBoundaryTest`).
- [ ] No new `pom.xml` deps (in-house robots parser; existing Resilience4j) — if Resilience4j missing, REPORT and let parent decide
- [ ] `DiscoveryHttpConfig` registers `RestClient` bean named `robotsRestClient`
- [ ] No regression on 01a / 01b tests
- [ ] `DiscoveryBoundaryTest` (full) passes
- [ ] No nutrition / recipe / household / auth / preference / ai / planner / feedback / adaptation-pipeline module file touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/discovery/domain/service/DiscoverySource.java                       (public SPI)
NEW   src/main/java/com/example/mealprep/discovery/domain/service/RobotsTxtGate.java                         (public interface)
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/CandidateAiFilter.java            (internal interface)

NEW   src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryQuery.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/DiscoveryCandidate.java
NEW   src/main/java/com/example/mealprep/discovery/api/dto/ParsedRecipe.java                                 (record + 3 nested records)

NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/SourceRegistry.java
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/RateLimiterRegistry.java
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/ContentFingerprintHasher.java
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/NoopCandidateAiFilterConfiguration.java
NEW   src/main/java/com/example/mealprep/discovery/api/internal/InHouseRobotsTxtGate.java                    (HTTP-client adapter lives in api.internal per gotcha)

NEW   src/main/java/com/example/mealprep/discovery/config/DiscoveryHttpConfig.java                           (RestClient bean for robots.txt fetches)

NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoverySourceUnavailableException.java
NEW   src/main/java/com/example/mealprep/discovery/exception/ExtractionFailedException.java

NEW   src/main/java/com/example/mealprep/discovery/source/package-info.java                                  (empty package, just establishes the location for ArchUnit; one-line @Documented package annotation)

MOD   src/test/java/com/example/mealprep/discovery/DiscoveryBoundaryTest.java                                (append discoverySourceImplsLiveInSourcePackage rule)

NEW   src/test/java/com/example/mealprep/discovery/SourceRegistryTest.java                                   (unit: bean indexing + warn-on-missing-impl + circuit-breaker query)
NEW   src/test/java/com/example/mealprep/discovery/RobotsTxtGateIT.java                                      (WireMock: allowed/disallowed/404/timeout outcomes + cache TTL)
NEW   src/test/java/com/example/mealprep/discovery/RateLimiterRegistryTest.java                             (unit: budget per source + cross-source independence)
NEW   src/test/java/com/example/mealprep/discovery/ContentFingerprintHasherTest.java                        (unit: stability under whitespace/case/order + 64-char width)
NEW   src/test/java/com/example/mealprep/discovery/CandidateAiFilterPassThroughTest.java                    (unit: returns input + WARN log)
NEW   src/test/java/com/example/mealprep/discovery/CandidateAiFilterOverrideTest.java                       (IT: a @TestConfiguration @Primary CandidateAiFilter bean supersedes the Noop)
```

Count: ~22 files. Concentration on the registry + the robots gate + the fingerprint hasher. Estimated agent runtime 45-55 min (the robots WireMock IT alone is ~15 min).

**Files this ticket does NOT modify**:
- `pom.xml` (no new deps; in-house robots parser; existing Resilience4j or fallback)
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- Other modules' files
- Discovery 01a entities, migrations, repos (frozen)
- Discovery 01b service impl + controllers + exception handler (frozen)
- `src/main/resources/openapi/openapi.yaml` (no API surface changes)

## Dependencies

- **Hard dependency**: `discovery-01a` (merged) — entities, repos, enums, `DiscoveryProperties`, async config, boundary test, exception root.
- **Hard dependency**: `discovery-01b` (merged) — service impl, controllers, exception handler. 01c doesn't modify any of them; only the registry consumes the repo references.
- **Hard dependency**: `core` (merged) — `MealPrepException` root.
- **Soft dependency**: Resilience4j (already in pom from earlier wave) — if absent, 01c falls back to an in-house token bucket (note in report).
- **Sibling tickets running in parallel** (Wave 3 round 3): `planner-01c`, `feedback-01c`, `adaptation-pipeline-01c`. None should touch any discovery file. Shared cross-cutting files: NONE — 01c adds zero OpenAPI / pom / config changes outside the discovery module.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `DiscoveryBoundaryTest` (now with new rule) passes — vacuously true today; trips on misplaced impls in 01e
- [ ] `NoopCandidateAiFilterConfiguration` uses `@Configuration` + `@Bean` factory (NOT `@Component @ConditionalOnMissingBean` on the impl class) — round-5 bug 1 avoidance
- [ ] `NoopCandidateAiFilterConfiguration` class name and `defaultCandidateAiFilter` method name produce DIFFERENT bean names — round-5 bug 2 avoidance
- [ ] No `pom.xml` adds (or one-line note in report if Resilience4j missing and falls back to in-house bucket)
- [ ] No other modules touched
- [ ] `RestClient` adapter for robots.txt lives in `discovery/api/internal/` or `discovery/config/` — NOT `domain.service.internal`. Verified via grep / springWebStaysInApi project rule

## Gotchas embedded (apply during implementation)

- **HTTP-client adapter location (RestClient / WebClient)**: must live in `..api..` or `..config..` — the project's ArchUnit `springWebStaysInApi` rule forbids Spring Web types in `domain.service.internal`. Recipe-01b's `UrlFetcher` got caught by this rule in iter 1 and was relocated. Bake the convention in upfront: `InHouseRobotsTxtGate` goes in `discovery/api/internal/`; the `RestClient` bean factory in `discovery/config/DiscoveryHttpConfig.java`. The `RobotsTxtGate` interface itself stays in `domain.service` because it's a pure interface with no Spring-Web imports.

- **SPI-with-Noop pattern (4-bug cluster)**: per [agent-prompt-template.md §SPI-with-Noop gotcha](../../../ai-workflow/templates/agent-prompt-template.md), the correct shape is `@Configuration` class with `@Bean @ConditionalOnMissingBean` method. DO NOT use `@Component @ConditionalOnMissingBean` on the impl class. The bug-1 manifestation: the conditional fires during component-scan, before other beans (test configs, sibling-module configs) register; the Noop registers unconditionally, then a real impl appears later → `NoUniqueBeanDefinitionException`. Bug-2 manifestation: a `@Bean` method named the same (case-insensitively) as the enclosing `@Configuration` class — both register a bean with the same auto-generated name → `BeanDefinitionOverrideException` at startup. **01c's `NoopCandidateAiFilterConfiguration` follows the recipe exactly**: class name `NoopCandidateAiFilterConfiguration` (bean `noopCandidateAiFilterConfiguration`), method name `defaultCandidateAiFilter` (bean `defaultCandidateAiFilter`) — DIFFERENT.

- **`InHouseRobotsTxtGate` uses `@Component @ConditionalOnMissingBean(RobotsTxtGate.class)` directly** — that's OK here because there's no other path to register the bean; the conditional fires at component-scan with no race against test configs. **01c's `InHouseRobotsTxtGate` is the v1 default; a future Crawler-Commons impl can replace it via the SPI-with-Noop pattern**. **Worth user review** — alternative is to wrap in `@Configuration` for symmetry; rejected because the gate is the actual production impl, not a Noop placeholder.

- **`@Type(JsonBinaryType.class)`** — N/A in 01c (no entities).

- **`saveAndFlush` vs `save`** — N/A in 01c (no DB writes in the helpers; `SourceRegistry.recordSuccess/Failure` use `save` because no response payload depends on the version bump; the bookkeeping is fire-and-forget).

- **`@TransactionalEventListener` + `@Transactional` propagation** — N/A in 01c (no event listeners ship; 01d adds the runner's listener).

- **Resilience4j availability**: verify before assuming. If absent, fall back to a tiny in-house token bucket — the LLD allows this latitude ("Resilience4j `@RateLimiter` keyed by source_key, configured at runtime"); the runtime keying is the load-bearing constraint, not the specific library. **Worth user review.**

- **Concurrent map for per-host robots cache**: `ConcurrentHashMap` is fine for v1. Thundering-herd on cache miss is acceptable (per-host cost is one extra HTTP GET).

- **Don't trust LLD column widths blindly** — N/A in 01c (no schema changes).

## What's NOT in scope

- The actual async runner — `DiscoveryJobRunner.run`, state transitions QUEUED → RUNNING → SUCCEEDED/FAILED/PARTIAL, per-step transactions, search/AI-filter/fetch loop, hard-constraint filter call, RecipeWriteApi.saveImportedRecipe handoff, DiscoveryRecipeIngestedEvent, DiscoveryJobCompletedEvent, orphan sweep impl, in-memory cancellation flag, `findByContentFingerprintAndOccurredAtAfter` repo method → **discovery-01d**
- Curated source seed (`R__discovery_seed_source_registry.sql` populated with ~25-30 INSERTs) + a reference curated `DiscoverySource` impl + Google CSE adapter (`SEARCH` source type) → **discovery-01e**
- Sync admin endpoint + `runJobSync` + `CompletableFuture` coordination + 408 / 502 mappings → **discovery-01f**
- `DiscoveryService` injected only by `planner.*` and `recipe.*` ArchUnit rule (LLD line 619) — defer to 01d
- Crawler-Commons library — separate `chore:` ticket if production traffic surfaces edge cases (`Allow:` precedence, wildcard paths). The in-house parser ships in 01c and covers the common case.

Squash-merge with: `feat(discovery): 01c — DiscoverySource SPI + source registry + robots gate + rate limiter + fingerprint hasher + AI filter no-op`
