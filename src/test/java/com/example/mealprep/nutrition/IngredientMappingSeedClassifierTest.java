package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.IngredientMappingSeedRow;
import com.example.mealprep.nutrition.api.dto.IngredientMappingSource;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import com.example.mealprep.nutrition.domain.service.internal.IngredientMappingSeedServiceImpl;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of the G05 seed classifier (insert / identical / collision) — the logic that
 * decides whether an existing row is byte-equivalent to what the seed would write. Pure static
 * functions; carries the Pitest load for the seed path (the IT proves end-to-end behaviour).
 */
class IngredientMappingSeedClassifierTest {

  private static IngredientMappingSeedRow row(
      String term, IngredientMappingSource source, String externalId) {
    return new IngredientMappingSeedRow(
        term,
        source,
        externalId,
        "consumed-basis; spike canon corpus@abc",
        new IngredientMappingSeedRow.SeedNutrition(
            new BigDecimal("130.0"),
            new BigDecimal("2.69"),
            new BigDecimal("28.17"),
            new BigDecimal("0.28"),
            new BigDecimal("0.4"),
            new BigDecimal("0.077"),
            new LinkedHashMap<>(
                Map.of(
                    "iron_mg", new BigDecimal("0.20"),
                    "saturated_fat_g", new BigDecimal("0.077")))));
  }

  private static IngredientMapping entityFor(IngredientMappingSeedRow seedRow) {
    return IngredientMapping.builder()
        .id(UUID.randomUUID())
        .searchTerm(seedRow.searchTerm())
        .source(seedRow.source())
        .externalId(seedRow.externalId())
        .nutritionPer100g(
            IngredientMappingSeedServiceImpl.buildDocument(seedRow.nutritionPer100g()))
        .confidence(new BigDecimal("1.000"))
        .needsReview(false)
        .build();
  }

  @Test
  void identicalRowClassifiesAsNoDivergence() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(entityFor(seedRow), seedRow))
        .isNull();
  }

  @Test
  void bigDecimalScaleDifferencesAreNotCollisions() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(seedRow);
    // same numbers, different scale (0.20 vs 0.2): numerically equal, never a collision
    existing.setNutritionPer100g(
        new IngredientNutritionDocument(
            130,
            new BigDecimal("2.690"),
            new BigDecimal("28.170"),
            new BigDecimal("0.280"),
            new BigDecimal("0.40"),
            new BigDecimal("0.0770"),
            null,
            Map.of(
                "iron_mg", new BigDecimal("0.2"),
                "saturated_fat_g", new BigDecimal("0.0770")),
            Map.of()));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow)).isNull();
  }

  @Test
  void nullAndEmptyVitaminsAreEquivalent() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(seedRow);
    IngredientNutritionDocument doc = existing.getNutritionPer100g();
    existing.setNutritionPer100g(
        new IngredientNutritionDocument(
            doc.calories(),
            doc.proteinG(),
            doc.carbsG(),
            doc.fatG(),
            doc.fibreG(),
            doc.saturatedFatG(),
            doc.sugarG(),
            doc.micros(),
            null)); // JSONB round-trip may hand back null instead of {}
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow)).isNull();
  }

  @Test
  void sourceDivergenceDetectedFirst() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(row("rice", IngredientMappingSource.MANUAL, "169757"));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("source");
  }

  @Test
  void externalIdDivergenceDetected() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(row("rice", IngredientMappingSource.USDA, "2512381"));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("externalId");
    // null-vs-value externalId is also a divergence, not an NPE
    IngredientMapping nullExternal = entityFor(row("rice", IngredientMappingSource.USDA, null));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(nullExternal, seedRow))
        .isEqualTo("externalId");
  }

  @Test
  void caloriesComparedAfterHalfUpRounding() {
    // seed calories 130.0 rounds to 130; an existing 130 is identical, 131 diverges
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(seedRow);
    IngredientNutritionDocument doc = existing.getNutritionPer100g();
    assertThat(doc.calories()).isEqualTo(130);
    existing.setNutritionPer100g(
        new IngredientNutritionDocument(
            131,
            doc.proteinG(),
            doc.carbsG(),
            doc.fatG(),
            doc.fibreG(),
            doc.saturatedFatG(),
            doc.sugarG(),
            doc.micros(),
            doc.vitamins()));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("nutritionPer100g.calories");
  }

  @Test
  void microValueAndKeySetDivergencesNameTheKey() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(seedRow);
    IngredientNutritionDocument doc = existing.getNutritionPer100g();
    // different value under the same key
    existing.setNutritionPer100g(
        withMicros(
            doc,
            Map.of("iron_mg", new BigDecimal("9.99"), "saturated_fat_g", new BigDecimal("0.077"))));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("nutritionPer100g.micros.iron_mg");
    // missing key on the existing side
    existing.setNutritionPer100g(
        withMicros(doc, Map.of("saturated_fat_g", new BigDecimal("0.077"))));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("nutritionPer100g.micros.iron_mg");
    // extra key on the existing side
    Map<String, BigDecimal> extra = new LinkedHashMap<>(doc.micros());
    extra.put("zinc_mg", BigDecimal.ONE);
    existing.setNutritionPer100g(withMicros(doc, extra));
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("nutritionPer100g.micros.zinc_mg");
  }

  @Test
  void missingDocumentOnExistingRowIsACollision() {
    IngredientMappingSeedRow seedRow = row("rice", IngredientMappingSource.USDA, "169757");
    IngredientMapping existing = entityFor(seedRow);
    existing.setNutritionPer100g(null);
    assertThat(IngredientMappingSeedServiceImpl.firstDivergingField(existing, seedRow))
        .isEqualTo("nutritionPer100g");
  }

  @Test
  void rejectionReasons() {
    assertThat(
            IngredientMappingSeedServiceImpl.rejectionReason(
                row("rice", IngredientMappingSource.USDA, "169757")))
        .isNull();
    assertThat(
            IngredientMappingSeedServiceImpl.rejectionReason(
                row(" Rice ", IngredientMappingSource.USDA, "169757")))
        .contains("normal-form");
    assertThat(
            IngredientMappingSeedServiceImpl.rejectionReason(
                row("double  space", IngredientMappingSource.USDA, "169757")))
        .contains("normal-form");
    assertThat(
            IngredientMappingSeedServiceImpl.rejectionReason(
                row(null, IngredientMappingSource.USDA, "169757")))
        .contains("searchTerm");
    assertThat(IngredientMappingSeedServiceImpl.rejectionReason(row("rice", null, null)))
        .contains("source");
    assertThat(
            IngredientMappingSeedServiceImpl.rejectionReason(
                new IngredientMappingSeedRow(
                    "rice", IngredientMappingSource.USDA, "169757", null, null)))
        .contains("nutritionPer100g");
  }

  @Test
  void buildDocumentRoundsCaloriesAndKeepsVitaminsEmpty() {
    IngredientNutritionDocument doc =
        IngredientMappingSeedServiceImpl.buildDocument(
            new IngredientMappingSeedRow.SeedNutrition(
                new BigDecimal("130.5"),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                Map.of("iron_mg", BigDecimal.ONE)));
    assertThat(doc.calories()).isEqualTo(131); // HALF_UP at document-build time (G04 note)
    assertThat(doc.vitamins()).isEmpty(); // recompute reads micros only; vitamins stays empty
    assertThat(doc.sugarG()).isNull();
    assertThat(doc.micros()).containsOnlyKeys("iron_mg");
  }

  private static IngredientNutritionDocument withMicros(
      IngredientNutritionDocument doc, Map<String, BigDecimal> micros) {
    return new IngredientNutritionDocument(
        doc.calories(),
        doc.proteinG(),
        doc.carbsG(),
        doc.fatG(),
        doc.fibreG(),
        doc.saturatedFatG(),
        doc.sugarG(),
        micros,
        doc.vitamins());
  }
}
