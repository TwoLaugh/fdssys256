package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.event.PriceObservedEvent;
import com.example.mealprep.grocery.exception.UnknownMappingKeyException;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Tier 4 APPEND-ONLY price-observation writer (01c). One {@link PriceObservation} row per
 * observation — a "correction" is a NEW row, NEVER an UPDATE (GROC-29 / GROC-32 override
 * behaviour). Per LLD lines 51 / 319 / 831 / 883. Package-private internal plumbing.
 *
 * <p><b>{@code confidence_weight}</b> is set at write time from {@link GroceryConfig} {@code
 * confidenceWeights} by {@code source} (paid=1.0, quote=0.85, manual=0.7, manual_estimated=0.4) and
 * never changed.
 *
 * <p><b>Unit normalisation.</b> {@code paid_unit_pence} is normalised to the ingredient's canonical
 * unit. The canonical unit is read from nutrition's PUBLIC query surface ({@link
 * NutritionQueryService#lookupIngredient(String)}): an ingredient whose mapping carries a {@code
 * defaultPieceGrams} is item-based → {@code per_item}; otherwise it is weight-based → {@code
 * per_100g} (nutrition stores nutrition per-100g, so per-100g is the canonical weight unit). When
 * the key is not in the nutrition cache (cross-module miss), v1 falls back to the DOCUMENTED
 * DEFAULT unit {@link #DEFAULT_UNIT} ({@code per_100g}) — flagged here as the v1 simplification.
 * The per-unit pence is computed from the supplied total pence + quantity when both are present;
 * otherwise the supplied total is stored as-is and the canonical unit recorded for later
 * re-normalisation.
 *
 * <p>Rejects writes for an {@code ingredientMappingKey} that is blank after normalisation ({@link
 * UnknownMappingKeyException} → 400).
 *
 * <p>Publishes {@link PriceObservedEvent} via {@link ApplicationEventPublisher} — consumers listen
 * {@code @TransactionalEventListener(AFTER_COMMIT)} so it effectively fires after commit (the
 * project-wide convention).
 */
@Component
class PriceObservationWriter {

  /** Documented v1 default canonical unit when the nutrition preferred-unit read misses. */
  static final String DEFAULT_UNIT = "per_100g";

  static final String UNIT_PER_ITEM = "per_item";

  private final PriceDataGateway gateway;
  private final GroceryConfig config;
  private final NutritionQueryService nutritionQueryService;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  PriceObservationWriter(
      PriceDataGateway gateway,
      GroceryConfig config,
      NutritionQueryService nutritionQueryService,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.gateway = gateway;
    this.config = config;
    this.nutritionQueryService = nutritionQueryService;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /** Command carrying everything needed to append one observation. */
  record WriteCommand(
      UUID userId,
      UUID householdId,
      String ingredientMappingKey,
      String store,
      String providerProductId,
      Integer packSizeG,
      Integer packCount,
      BigDecimal quantity,
      String quantityUnit,
      Integer paidTotalPence,
      String currency,
      PriceSource source,
      UUID groceryOrderId,
      UUID shoppingListLineId,
      Instant observedAt,
      String note) {}

  /**
   * Append one observation. Normalises the key, resolves the canonical unit, computes the per-unit
   * pence, persists the row and publishes {@link PriceObservedEvent}. Returns the persisted entity.
   */
  PriceObservation write(WriteCommand cmd) {
    String key = IngredientMappingKeys.normalise(cmd.ingredientMappingKey());
    if (key == null || key.isEmpty()) {
      throw new UnknownMappingKeyException(cmd.ingredientMappingKey());
    }

    String canonicalUnit = resolveCanonicalUnit(key);
    Integer unitPence = computeUnitPence(cmd.paidTotalPence(), cmd.quantity());
    BigDecimal weight = weightFor(cmd.source());
    Instant observedAt = cmd.observedAt() != null ? cmd.observedAt() : clock.instant();
    String currency = cmd.currency() != null ? cmd.currency() : "GBP";

    PriceObservation row =
        PriceObservation.builder()
            .id(UUID.randomUUID())
            .userId(cmd.userId())
            .householdId(cmd.householdId())
            .ingredientMappingKey(key)
            .store(cmd.store())
            .providerProductId(cmd.providerProductId())
            .packSizeG(cmd.packSizeG())
            .packCount(cmd.packCount())
            .quantity(cmd.quantity())
            .quantityUnit(cmd.quantityUnit() != null ? cmd.quantityUnit() : canonicalUnit)
            .paidUnitPence(unitPence)
            .paidTotalPence(cmd.paidTotalPence())
            .currency(currency)
            .source(cmd.source())
            .confidenceWeight(weight)
            .groceryOrderId(cmd.groceryOrderId())
            .shoppingListLineId(cmd.shoppingListLineId())
            .observedAt(observedAt)
            .note(cmd.note())
            .build();

    PriceObservation saved = gateway.save(row);

    eventPublisher.publishEvent(
        new PriceObservedEvent(
            saved.getId(),
            saved.getUserId(),
            saved.getHouseholdId(),
            saved.getIngredientMappingKey(),
            saved.getStore(),
            saved.getSource(),
            saved.getPaidUnitPence(),
            saved.getObservedAt(),
            clock.instant()));

    return saved;
  }

  /**
   * Canonical unit via nutrition's PUBLIC query surface. Item-based ({@code defaultPieceGrams !=
   * null}) → {@code per_item}; weight-based → {@code per_100g}; cache miss → documented default.
   */
  String resolveCanonicalUnit(String normalisedKey) {
    return nutritionQueryService
        .lookupIngredient(normalisedKey)
        .map(PriceObservationWriter::unitFor)
        .orElse(DEFAULT_UNIT);
  }

  private static String unitFor(IngredientNutritionDto dto) {
    return dto.defaultPieceGrams() != null ? UNIT_PER_ITEM : DEFAULT_UNIT;
  }

  /**
   * Per-unit pence = total / quantity when both present (rounded to integer pence); otherwise the
   * supplied total as-is (or null when no total was supplied). Quantity ≤ 0 falls back to the
   * total.
   */
  static Integer computeUnitPence(Integer paidTotalPence, BigDecimal quantity) {
    if (paidTotalPence == null) {
      return null;
    }
    if (quantity == null || quantity.signum() <= 0) {
      return paidTotalPence;
    }
    return BigDecimal.valueOf(paidTotalPence)
        .divide(quantity, 0, RoundingMode.HALF_UP)
        .intValueExact();
  }

  private BigDecimal weightFor(PriceSource source) {
    GroceryConfig.ConfidenceWeightsConfig w = config.confidenceWeights();
    double value =
        switch (source) {
          case PAID -> w.paid();
          case QUOTE -> w.quote();
          case MANUAL -> w.manual();
          case MANUAL_ESTIMATED -> w.manualEstimated();
          case INFLATION_INDEXED -> w.inflationIndexed();
        };
    return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
  }
}
