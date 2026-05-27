package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.config.GroceryConfig;
import com.example.mealprep.grocery.domain.entity.PriceObservation;
import com.example.mealprep.grocery.domain.entity.PriceSource;
import com.example.mealprep.grocery.event.PriceObservedEvent;
import com.example.mealprep.grocery.exception.UnknownMappingKeyException;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for the APPEND-ONLY {@link PriceObservationWriter} (01c). Verifies: source→confidence
 * weight mapping, per-unit-pence computation, canonical-unit resolution (nutrition preferred-unit
 * with documented fallback), key normalisation before persist, the blank-key rejection, the
 * defaults (observedAt/currency/quantityUnit), and that every write persists exactly one row +
 * fires one {@link PriceObservedEvent}. Pure unit test — all collaborators mocked.
 */
@ExtendWith(MockitoExtension.class)
class PriceObservationWriterTest {

  private static final Instant NOW = Instant.parse("2026-05-27T12:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final GroceryConfig config =
      new GroceryConfig(
          new GroceryConfig.AggregatorConfig(90, 2.0, 90),
          new GroceryConfig.ConfidenceWeightsConfig(1.0, 0.85, 0.7, 0.4, 0.15),
          new GroceryConfig.InflationConfig(0.005),
          new GroceryConfig.FreshnessConfig(8, 50),
          new GroceryConfig.SchedulerConfig("0 0 4 * * SUN", "0 0 * * * *", "0 0 5 * * *"),
          new GroceryConfig.OrderConfig(300, 24));

  @Mock private PriceDataGateway gateway;
  @Mock private NutritionQueryService nutritionQueryService;
  @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

  private PriceObservationWriter writer() {
    return new PriceObservationWriter(
        gateway, config, nutritionQueryService, eventPublisher, clock);
  }

  /** A WriteCommand with sensible defaults; tests override the fields they exercise. */
  private PriceObservationWriter.WriteCommand cmd(
      String key, Integer totalPence, BigDecimal quantity, PriceSource source) {
    return new PriceObservationWriter.WriteCommand(
        java.util.UUID.randomUUID(), // userId
        null, // householdId
        key,
        "tesco_online",
        null, // providerProductId
        null, // packSizeG
        null, // packCount
        quantity,
        null, // quantityUnit (defaults to canonical)
        totalPence,
        null, // currency (defaults GBP)
        source,
        null, // groceryOrderId
        null, // shoppingListLineId
        null, // observedAt (defaults to clock)
        null); // note
  }

  private PriceObservation captureSaved() {
    ArgumentCaptor<PriceObservation> captor = ArgumentCaptor.forClass(PriceObservation.class);
    verify(gateway).save(captor.capture());
    return captor.getValue();
  }

  private void stubSaveEchoesArg() {
    when(gateway.save(any(PriceObservation.class)))
        .thenAnswer(inv -> inv.getArgument(0, PriceObservation.class));
  }

  // ---------------- confidence weight per source ----------------

  @Test
  void write_setsConfidenceWeightFromSource_paid() {
    stubSaveEchoesArg();
    when(nutritionQueryService.lookupIngredient("chicken breast")).thenReturn(Optional.empty());

    writer().write(cmd("chicken breast", 200, BigDecimal.ONE, PriceSource.PAID));

    assertThat(captureSaved().getConfidenceWeight()).isEqualByComparingTo(new BigDecimal("1.000"));
  }

  @Test
  void write_setsConfidenceWeightFromSource_manualEstimatedIsLowest() {
    stubSaveEchoesArg();
    when(nutritionQueryService.lookupIngredient("chicken breast")).thenReturn(Optional.empty());

    writer().write(cmd("chicken breast", 200, BigDecimal.ONE, PriceSource.MANUAL_ESTIMATED));

    assertThat(captureSaved().getConfidenceWeight()).isEqualByComparingTo(new BigDecimal("0.400"));
  }

  // ---------------- per-unit pence ----------------

  @Test
  void computeUnitPence_dividesTotalByQuantity_halfUp() {
    // 270 / 1.7 = 158.8 → 159 (HALF_UP).
    assertThat(PriceObservationWriter.computeUnitPence(270, new BigDecimal("1.7"))).isEqualTo(159);
  }

  @Test
  void computeUnitPence_nullTotal_returnsNull() {
    assertThat(PriceObservationWriter.computeUnitPence(null, BigDecimal.TEN)).isNull();
  }

  @Test
  void computeUnitPence_nonPositiveQuantity_fallsBackToTotal() {
    assertThat(PriceObservationWriter.computeUnitPence(250, BigDecimal.ZERO)).isEqualTo(250);
    assertThat(PriceObservationWriter.computeUnitPence(250, null)).isEqualTo(250);
  }

  @Test
  void write_storesComputedPerUnitPence() {
    stubSaveEchoesArg();
    when(nutritionQueryService.lookupIngredient("rice")).thenReturn(Optional.empty());

    writer().write(cmd("rice", 200, new BigDecimal("2"), PriceSource.PAID));

    assertThat(captureSaved().getPaidUnitPence()).isEqualTo(100);
  }

  // ---------------- canonical unit resolution ----------------

  @Test
  void resolveCanonicalUnit_itemBased_whenDefaultPieceGramsPresent() {
    IngredientNutritionDto itemBased = org.mockito.Mockito.mock(IngredientNutritionDto.class);
    when(itemBased.defaultPieceGrams()).thenReturn(50);
    when(nutritionQueryService.lookupIngredient("egg")).thenReturn(Optional.of(itemBased));

    assertThat(writer().resolveCanonicalUnit("egg"))
        .isEqualTo(PriceObservationWriter.UNIT_PER_ITEM);
  }

  @Test
  void resolveCanonicalUnit_weightBased_whenNoDefaultPieceGrams() {
    IngredientNutritionDto weightBased = org.mockito.Mockito.mock(IngredientNutritionDto.class);
    when(weightBased.defaultPieceGrams()).thenReturn(null);
    when(nutritionQueryService.lookupIngredient("flour")).thenReturn(Optional.of(weightBased));

    assertThat(writer().resolveCanonicalUnit("flour"))
        .isEqualTo(PriceObservationWriter.DEFAULT_UNIT);
  }

  @Test
  void resolveCanonicalUnit_cacheMiss_fallsBackToDocumentedDefault() {
    when(nutritionQueryService.lookupIngredient("novel")).thenReturn(Optional.empty());

    assertThat(writer().resolveCanonicalUnit("novel"))
        .isEqualTo(PriceObservationWriter.DEFAULT_UNIT);
  }

  // ---------------- normalisation + rejection ----------------

  @Test
  void write_normalisesKeyBeforePersist() {
    stubSaveEchoesArg();
    when(nutritionQueryService.lookupIngredient("chicken breast")).thenReturn(Optional.empty());

    writer().write(cmd("  Chicken   Breast ", 100, BigDecimal.ONE, PriceSource.PAID));

    assertThat(captureSaved().getIngredientMappingKey()).isEqualTo("chicken breast");
  }

  @Test
  void write_blankKey_throwsUnknownMappingKey_andDoesNotPersist() {
    assertThatThrownBy(() -> writer().write(cmd("   ", 100, BigDecimal.ONE, PriceSource.PAID)))
        .isInstanceOf(UnknownMappingKeyException.class);
    verify(gateway, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void write_nullKey_throwsUnknownMappingKey() {
    assertThatThrownBy(() -> writer().write(cmd(null, 100, BigDecimal.ONE, PriceSource.PAID)))
        .isInstanceOf(UnknownMappingKeyException.class);
    verify(gateway, never()).save(any());
  }

  // ---------------- defaults + event ----------------

  @Test
  void write_defaultsObservedAtToClock_currencyToGbp_unitToCanonical() {
    stubSaveEchoesArg();
    when(nutritionQueryService.lookupIngredient("rice")).thenReturn(Optional.empty());

    writer().write(cmd("rice", 100, BigDecimal.ONE, PriceSource.PAID));

    PriceObservation saved = captureSaved();
    assertThat(saved.getObservedAt()).isEqualTo(NOW);
    assertThat(saved.getCurrency()).isEqualTo("GBP");
    assertThat(saved.getQuantityUnit()).isEqualTo(PriceObservationWriter.DEFAULT_UNIT);
  }

  @Test
  void write_publishesExactlyOnePriceObservedEvent() {
    stubSaveEchoesArg();
    when(nutritionQueryService.lookupIngredient("rice")).thenReturn(Optional.empty());

    writer().write(cmd("rice", 100, BigDecimal.ONE, PriceSource.PAID));

    ArgumentCaptor<PriceObservedEvent> captor = ArgumentCaptor.forClass(PriceObservedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().ingredientMappingKey()).isEqualTo("rice");
  }
}
