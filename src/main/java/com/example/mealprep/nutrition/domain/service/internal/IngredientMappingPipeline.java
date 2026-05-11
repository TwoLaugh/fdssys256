package com.example.mealprep.nutrition.domain.service.internal;

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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache-check → AI-parse-skip → USDA-search → OFF-fallback → AI-match-skip → persist (per LLD Flow
 * 6 lines 970-982). The AI parse + match steps are stubbed in 01d — the LLD owner is the AI
 * module's task catalogue, which is not yet built. See the ticket's "AI steps stubbed" divergence
 * note.
 *
 * <p>Joins the caller's transaction ({@code @Transactional} default REQUIRED): when called inside
 * {@code correctIngredientMapping} or future {@code logSnack}, the persist happens in the same
 * unit-of-work. The HTTP calls happen inside the transaction; long latency is a known trade-off per
 * LLD line 982.
 *
 * <p>Concurrent inserts of the same {@code searchTerm} race; the loser re-reads and uses the
 * winner's row (LLD line 979 — "no retry storm"). This is implemented via {@link
 * DataIntegrityViolationException} handling in {@link #persistOrReread}.
 */
@Component
public class IngredientMappingPipeline {

  private static final Logger log = LoggerFactory.getLogger(IngredientMappingPipeline.class);

  /** Cap for source-derived confidence — without AI re-ranking we don't trust above this. */
  private static final double CONFIDENCE_CAP = 0.85;

  /** Below this, {@code needsReview} flips on (LLD line 979). */
  private static final double REVIEW_THRESHOLD = 0.7;

  private final IngredientMappingRepository repo;
  private final IntakeKeyNormaliser normaliser;
  private final UsdaApiClient usdaClient;
  private final OpenFoodFactsClient offClient;
  private final IngredientMappingMapper mapper;

  public IngredientMappingPipeline(
      IngredientMappingRepository repo,
      IntakeKeyNormaliser normaliser,
      UsdaApiClient usdaClient,
      OpenFoodFactsClient offClient,
      IngredientMappingMapper mapper) {
    this.repo = repo;
    this.normaliser = normaliser;
    this.usdaClient = usdaClient;
    this.offClient = offClient;
    this.mapper = mapper;
  }

  @Transactional
  public IngredientMappingResult resolve(IngredientLookupInput input) {
    String searchTerm = normaliser.normalise(input.rawTerm());
    if (searchTerm == null || searchTerm.isEmpty()) {
      return new IngredientMappingResult.Unmapped(
          new UnmappedIngredientDto(input.rawTerm(), "empty term", BigDecimal.ZERO));
    }

    Optional<IngredientMapping> hit = repo.findBySearchTerm(searchTerm);
    if (hit.isPresent()) {
      return new IngredientMappingResult.Resolved(mapper.toDto(hit.get()));
    }

    // Step 3 (AI parse) skipped in 01d — use the normalised term as the USDA search term verbatim.
    Optional<UsdaSearchResultDto> usda = usdaClient.search(searchTerm);
    if (usda.isPresent() && usda.get().foods() != null && !usda.get().foods().isEmpty()) {
      // Step 5 (AI match) skipped — take the first (highest USDA score) result.
      UsdaSearchResultDto.Food first = usda.get().foods().get(0);
      double confidence = Math.min(first.score() == null ? 0.5 : first.score(), CONFIDENCE_CAP);
      return new IngredientMappingResult.Resolved(
          persistOrReread(
              searchTerm,
              IngredientMappingSource.USDA,
              first.fdcIdString(),
              first.toDocument(),
              confidence));
    }

    Optional<OffSearchResultDto> off = offClient.search(searchTerm);
    if (off.isPresent() && off.get().products() != null && !off.get().products().isEmpty()) {
      OffSearchResultDto.Product first = off.get().products().get(0);
      double confidence = Math.min(first.score(), CONFIDENCE_CAP);
      return new IngredientMappingResult.Resolved(
          persistOrReread(
              searchTerm,
              IngredientMappingSource.OPEN_FOOD_FACTS,
              first.code(),
              first.toDocument(),
              confidence));
    }

    return new IngredientMappingResult.Unmapped(
        new UnmappedIngredientDto(input.rawTerm(), "no source matches", BigDecimal.ZERO));
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
}
