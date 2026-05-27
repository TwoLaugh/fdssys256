package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.domain.service.internal.ReferenceProductMapper.ReferenceProduct;
import com.example.mealprep.grocery.domain.service.internal.ReferenceProductMapper.RolledReference;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The product→mapping-key roll-up. Verifies the per-key mean, key normalisation, unit consistency
 * and determinism (the load-bearing judgement layer the ticket flags "Worth user review").
 */
class ReferenceProductMapperTest {

  private final ReferenceProductMapper mapper = new ReferenceProductMapper();

  @Test
  void roll_meansPerKey_andCountsSampleProducts() {
    Map<String, RolledReference> out =
        mapper.roll(
            List.of(
                new ReferenceProduct("chicken breast", 100, "per_100g"),
                new ReferenceProduct("chicken breast", 120, "per_100g"),
                new ReferenceProduct("chicken breast", 110, "per_100g")));

    RolledReference chicken = out.get("chicken breast");
    assertThat(chicken.unitPence()).isEqualTo(110); // mean(100,120,110)
    assertThat(chicken.sampleProducts()).isEqualTo(3);
    assertThat(chicken.unit()).isEqualTo("per_100g");
  }

  @Test
  void roll_normalisesKeys_collapsingCaseAndWhitespace() {
    Map<String, RolledReference> out =
        mapper.roll(
            List.of(
                new ReferenceProduct("Chicken  Breast", 100, "per_100g"),
                new ReferenceProduct("chicken breast", 140, "per_100g")));
    // Both products roll into the single normalised key with mean 120.
    assertThat(out).containsOnlyKeys("chicken breast");
    assertThat(out.get("chicken breast").unitPence()).isEqualTo(120);
    assertThat(out.get("chicken breast").sampleProducts()).isEqualTo(2);
  }

  @Test
  void roll_keepsUnitConsistent_usesDominantUnit() {
    // Two per_100g + one per_item → dominant unit per_100g; only matching products contribute.
    Map<String, RolledReference> out =
        mapper.roll(
            List.of(
                new ReferenceProduct("rice", 10, "per_100g"),
                new ReferenceProduct("rice", 20, "per_100g"),
                new ReferenceProduct("rice", 999, "per_item")));
    RolledReference rice = out.get("rice");
    assertThat(rice.unit()).isEqualTo("per_100g");
    assertThat(rice.unitPence()).isEqualTo(15); // mean(10,20); the per_item outlier is excluded
    assertThat(rice.sampleProducts()).isEqualTo(2);
  }

  @Test
  void roll_dropsBlankKeys() {
    Map<String, RolledReference> out =
        mapper.roll(
            List.of(
                new ReferenceProduct("  ", 100, "per_100g"),
                new ReferenceProduct("salt", 8, "per_100g")));
    assertThat(out).containsOnlyKeys("salt");
  }

  @Test
  void roll_isDeterministic_preservesFirstSeenKeyOrder() {
    Map<String, RolledReference> out =
        mapper.roll(
            List.of(
                new ReferenceProduct("broccoli", 30, "per_100g"),
                new ReferenceProduct("chicken breast", 110, "per_100g"),
                new ReferenceProduct("broccoli", 32, "per_100g")));
    assertThat(out.keySet()).containsExactly("broccoli", "chicken breast");
  }
}
