package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.api.dto.UnmappedIngredientDto;
import com.example.mealprep.nutrition.api.mapper.IngredientMappingMapper;
import com.example.mealprep.nutrition.config.OffSearchResultDto;
import com.example.mealprep.nutrition.config.OpenFoodFactsClient;
import com.example.mealprep.nutrition.config.UsdaApiClient;
import com.example.mealprep.nutrition.config.UsdaSearchResultDto;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.repository.IngredientMappingRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache-check → AI-parse → USDA-search → OFF-fallback → AI-match → persist (per LLD Flow 6 lines
 * 970-982). The AI parse + match steps are wired through {@link AiService} (nutrition-01k); they
 * were stubbed in 01d while the AI task catalogue was unbuilt.
 *
 * <p><b>Step 3 — AI parse</b> ({@link IngredientParseTask}): turns the raw / normalised line into a
 * clean USDA search term (plus a structured parse). The cleaned term drives the USDA / OFF search.
 *
 * <p><b>Step 5 — AI match</b> ({@link IngredientMatchTask}): re-ranks the source candidates and
 * picks the best (or declines). The chosen candidate is persisted; with a genuine AI re-rank the
 * 0.85 source-score cap lifts (LLD line 982) and the model's own confidence is used.
 *
 * <p><b>Cost / caching.</b> The cache key is the <em>normalised</em> term and the cache-check is
 * the FIRST step — a repeat of the same ingredient short-circuits before ANY AI call, so each
 * unique term is parsed + matched at most once, then free forever. Both AI calls go through {@link
 * AiService} so the circuit breaker, token-cap, and two-scope {@code CostBudgetGuard} govern them;
 * both tasks are cheap-tier. The candidate list handed to the match task is bounded ({@link
 * #MAX_MATCH_CANDIDATES}) so a fat USDA response cannot bloat the prompt.
 *
 * <p><b>Graceful degrade (safety net preserved).</b> Every AI call is wrapped: if parse fails /
 * circuit-open / returns nothing usable, the pipeline uses the normalised term verbatim (the old
 * 01d behaviour). If match fails / circuit-open / returns "no good match", the pipeline falls back
 * to the deterministic first-hit (highest source score, capped at {@link #CONFIDENCE_CAP}) — the
 * original safety net. An AI outage never blocks the DB write.
 *
 * <p>Joins the caller's transaction ({@code @Transactional} default REQUIRED): when called inside
 * {@code correctIngredientMapping} or future {@code logSnack}, the persist happens in the same
 * unit-of-work. The HTTP / AI calls happen inside the transaction; long latency is a known
 * trade-off per LLD line 982.
 *
 * <p>Concurrent inserts of the same {@code searchTerm} race; the loser re-reads and uses the
 * winner's row (LLD line 979 — "no retry storm"). This is implemented via {@link
 * DataIntegrityViolationException} handling in {@link #persistOrReread}.
 */
@Component
public class IngredientMappingPipeline {

  private static final Logger log = LoggerFactory.getLogger(IngredientMappingPipeline.class);

  /** Cap for source-derived confidence when we FALL BACK to first-hit (no AI re-rank). */
  private static final double CONFIDENCE_CAP = 0.85;

  /** Below this, {@code needsReview} flips on (LLD line 979). */
  private static final double REVIEW_THRESHOLD = 0.7;

  /**
   * Upper bound on candidates handed to the AI match task. The source clients return text-ranked
   * hits; the best match is near the top, and a tight list keeps the match prompt (and its cost)
   * small. Cost discipline — the match never re-ranks an unbounded response.
   */
  private static final int MAX_MATCH_CANDIDATES = 8;

  private final IngredientMappingRepository repo;
  private final IntakeKeyNormaliser normaliser;
  private final UsdaApiClient usdaClient;
  private final OpenFoodFactsClient offClient;
  private final IngredientMappingMapper mapper;
  private final AiService aiService;

  public IngredientMappingPipeline(
      IngredientMappingRepository repo,
      IntakeKeyNormaliser normaliser,
      UsdaApiClient usdaClient,
      OpenFoodFactsClient offClient,
      IngredientMappingMapper mapper,
      AiService aiService) {
    this.repo = repo;
    this.normaliser = normaliser;
    this.usdaClient = usdaClient;
    this.offClient = offClient;
    this.mapper = mapper;
    this.aiService = aiService;
  }

  @Transactional
  public IngredientMappingResult resolve(IngredientLookupInput input) {
    String searchTerm = normaliser.normalise(input.rawTerm());
    if (searchTerm == null || searchTerm.isEmpty()) {
      return new IngredientMappingResult.Unmapped(
          new UnmappedIngredientDto(input.rawTerm(), "empty term", BigDecimal.ZERO));
    }

    // Step 1 — cache check FIRST. The cache key is the normalised term; a repeat of the same
    // ingredient short-circuits here, so the AI parse + match below run at most once per unique
    // term. Repeats are free (no AI cost).
    Optional<IngredientMapping> hit = repo.findBySearchTerm(searchTerm);
    if (hit.isPresent()) {
      return new IngredientMappingResult.Resolved(mapper.toDto(hit.get()));
    }

    // Step 3 — AI parse. Clean the line into a USDA search term; degrade to the normalised term
    // verbatim (the 01d behaviour) on any AI failure.
    String aiSearchTerm = parseSearchTerm(input.rawTerm(), searchTerm);

    Optional<UsdaSearchResultDto> usda = usdaClient.search(aiSearchTerm);
    if (usda.isPresent() && usda.get().foods() != null && !usda.get().foods().isEmpty()) {
      List<UsdaSearchResultDto.Food> foods = usda.get().foods();
      // Step 5 — AI match over the USDA candidates; degrades to the first (highest-score) hit.
      MatchSelection<UsdaSearchResultDto.Food> selection =
          selectMatch(
              input.rawTerm(),
              aiSearchTerm,
              foods,
              UsdaSearchResultDto.Food::fdcIdString,
              UsdaSearchResultDto.Food::description,
              "USDA",
              first -> first.score() == null ? 0.5 : first.score());
      UsdaSearchResultDto.Food chosen = selection.candidate();
      return new IngredientMappingResult.Resolved(
          persistOrReread(
              searchTerm,
              IngredientMappingSource.USDA,
              chosen.fdcIdString(),
              chosen.toDocument(),
              selection.confidence()));
    }

    Optional<OffSearchResultDto> off = offClient.search(aiSearchTerm);
    if (off.isPresent() && off.get().products() != null && !off.get().products().isEmpty()) {
      List<OffSearchResultDto.Product> products = off.get().products();
      MatchSelection<OffSearchResultDto.Product> selection =
          selectMatch(
              input.rawTerm(),
              aiSearchTerm,
              products,
              OffSearchResultDto.Product::code,
              OffSearchResultDto.Product::productName,
              "OFF",
              OffSearchResultDto.Product::score);
      OffSearchResultDto.Product chosen = selection.candidate();
      return new IngredientMappingResult.Resolved(
          persistOrReread(
              searchTerm,
              IngredientMappingSource.OPEN_FOOD_FACTS,
              chosen.code(),
              chosen.toDocument(),
              selection.confidence()));
    }

    return new IngredientMappingResult.Unmapped(
        new UnmappedIngredientDto(input.rawTerm(), "no source matches", BigDecimal.ZERO));
  }

  /**
   * AI parse (step 3). Returns the model's cleaned search term, or the normalised fallback term
   * when the AI call fails (circuit open, budget, parse error) or returns no usable term. Never
   * returns blank.
   */
  private String parseSearchTerm(String rawTerm, String normalisedTerm) {
    try {
      IngredientParseResult parsed =
          aiService.execute(new IngredientParseTask(rawTerm, normalisedTerm, null, null));
      if (parsed != null && parsed.searchTermOrNull() != null) {
        String term = parsed.searchTermOrNull();
        log.debug("AI parse term '{}' -> '{}'", normalisedTerm, term);
        return term;
      }
      log.debug(
          "AI parse returned no usable term for '{}' — using normalised term", normalisedTerm);
    } catch (RuntimeException ex) {
      // Skip-and-degrade: an AI fault (outage, circuit-open, budget, bad response) must never block
      // the mapping. Use the normalised term verbatim — exactly the pre-01k behaviour.
      log.warn(
          "AI parse failed for '{}' ({}: {}) — using normalised term verbatim",
          normalisedTerm,
          ex.getClass().getSimpleName(),
          ex.getMessage());
    }
    return normalisedTerm;
  }

  /**
   * AI match (step 5) over a source's candidate list, with the deterministic first-hit fallback as
   * the safety net. When the model picks a candidate, the 0.85 cap lifts and its confidence is used
   * (clamped to [0,1]); when it declines ("no good match"), the AI errors, or the breaker is open,
   * the pipeline takes the first (highest-score) candidate and re-applies the {@link
   * #CONFIDENCE_CAP}.
   */
  private <C> MatchSelection<C> selectMatch(
      String rawTerm,
      String searchTerm,
      List<C> candidates,
      java.util.function.Function<C, String> idOf,
      java.util.function.Function<C, String> descriptionOf,
      String sourceLabel,
      java.util.function.ToDoubleFunction<C> firstHitScore) {
    C firstHit = candidates.get(0);
    double fallbackConfidence = Math.min(firstHitScore.applyAsDouble(firstHit), CONFIDENCE_CAP);

    try {
      List<IngredientMatchTask.Candidate> descriptors = new ArrayList<>();
      int limit = Math.min(candidates.size(), MAX_MATCH_CANDIDATES);
      for (int i = 0; i < limit; i++) {
        C c = candidates.get(i);
        descriptors.add(
            new IngredientMatchTask.Candidate(
                sourceLabel, idOf.apply(c), nullToBlank(descriptionOf.apply(c))));
      }
      IngredientMatchTask task =
          new IngredientMatchTask(rawTerm, searchTerm, descriptors, null, null);
      IngredientMatchResult result = aiService.execute(task);
      if (result == null || result.isNoMatch()) {
        log.debug(
            "AI match declined for '{}' ({} candidates) — using first {} hit",
            searchTerm,
            descriptors.size(),
            sourceLabel);
        return new MatchSelection<>(firstHit, fallbackConfidence);
      }
      int index = result.chosenIndex();
      if (index < 0 || index >= limit) {
        // Out-of-range pick — treat as a non-answer and fall back rather than trust a bad index.
        log.warn(
            "AI match returned out-of-range index {} (limit {}) for '{}' — using first {} hit",
            index,
            limit,
            searchTerm,
            sourceLabel);
        return new MatchSelection<>(firstHit, fallbackConfidence);
      }
      C chosen = candidates.get(index);
      // Genuine AI re-rank — the 0.85 cap lifts (LLD line 982); use the model's own confidence.
      double confidence = clamp01(result.confidenceOrZero());
      log.debug("AI match picked index {} for '{}' (confidence {})", index, searchTerm, confidence);
      return new MatchSelection<>(chosen, confidence);
    } catch (RuntimeException ex) {
      // Skip-and-degrade: fall back to the deterministic first-hit (capped) — the original safety
      // net. An AI outage never changes whether the ingredient maps, only how it is ranked.
      log.warn(
          "AI match failed for '{}' ({}: {}) — using first {} hit",
          searchTerm,
          ex.getClass().getSimpleName(),
          ex.getMessage(),
          sourceLabel);
      return new MatchSelection<>(firstHit, fallbackConfidence);
    }
  }

  private IngredientNutritionDto persistOrReread(
      String searchTerm,
      IngredientMappingSource source,
      String externalId,
      IngredientNutritionDocument doc,
      double confidence) {
    boolean needsReview = confidence < REVIEW_THRESHOLD;
    IngredientMapping toSave =
        IngredientMapping.builder()
            .id(UUID.randomUUID())
            .searchTerm(searchTerm)
            .source(source)
            .externalId(externalId)
            .nutritionPer100g(doc)
            .confidence(BigDecimal.valueOf(confidence))
            .needsReview(needsReview)
            .build();
    try {
      // saveAndFlush so @CreationTimestamp / @UpdateTimestamp / @Version are materialised before
      // mapping to the response DTO.
      return mapper.toDto(repo.saveAndFlush(toSave));
    } catch (DataIntegrityViolationException race) {
      log.info("ingredient-mapping race resolved by re-read searchTerm={}", searchTerm);
      return mapper.toDto(
          repo.findBySearchTerm(searchTerm)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "race lost but no winner row found searchTerm=" + searchTerm, race)));
    }
  }

  private static String nullToBlank(String s) {
    return s == null ? "" : s;
  }

  private static double clamp01(double v) {
    if (v < 0.0) {
      return 0.0;
    }
    return Math.min(v, 1.0);
  }

  /** A chosen candidate from a source list together with the confidence to persist for it. */
  private record MatchSelection<C>(C candidate, double confidence) {}
}
