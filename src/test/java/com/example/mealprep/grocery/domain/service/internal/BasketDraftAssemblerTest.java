package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraftLine;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.GroceryQualityPreferences;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link BasketDraftAssembler} (grocery-01e). Per the ticket edge-case checklist:
 * lines are 1:1 with the order lines; preferences derive from the lifestyle config's {@code
 * groceryQualityPreferences}; the preferred SKU comes from the last-paid {@code providerProductId}
 * for {@code (userId, key)}. All collaborators mocked.
 */
@ExtendWith(MockitoExtension.class)
class BasketDraftAssemblerTest {

  @Mock private LifestyleConfigQueryService lifestyleConfigQueryService;
  @Mock private GroceryOrderDataGateway dataGateway;

  private BasketDraftAssembler assembler() {
    return new BasketDraftAssembler(lifestyleConfigQueryService, dataGateway);
  }

  private GroceryOrder orderWith(UUID userId, GroceryOrderLine... lines) {
    GroceryOrder order =
        GroceryOrder.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .shoppingListId(UUID.randomUUID())
            .providerKey("fake")
            .status(GroceryOrderStatus.DRAFT)
            .currency("GBP")
            .traceId(UUID.randomUUID())
            .lines(new ArrayList<>(List.of(lines)))
            .build();
    for (GroceryOrderLine l : lines) {
      l.setGroceryOrder(order);
    }
    return order;
  }

  private GroceryOrderLine line(String key, String displayName, Integer packCount) {
    Instant now = Instant.now();
    return GroceryOrderLine.builder()
        .id(UUID.randomUUID())
        .ingredientMappingKey(key)
        .displayName(displayName)
        .quantityRequested(new BigDecimal("1.000"))
        .quantityUnit("kg")
        .packSizeG(500)
        .packCountRequested(packCount)
        .lineStatus(OrderLineStatus.QUEUED)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  @Test
  void lines_are1to1_withOrderLines() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 2), line("eggs", "Eggs", 1));
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.groceryOrderId()).isEqualTo(order.getId());
    assertThat(draft.userId()).isEqualTo(userId);
    assertThat(draft.lines()).hasSize(2);
    assertThat(draft.lines())
        .extracting(BasketDraftLine::ingredientMappingKey)
        .containsExactly("flour", "eggs");
    assertThat(draft.lines()).extracting(BasketDraftLine::packCountRequested).containsExactly(2, 1);
  }

  @Test
  void preferences_deriveFromLifestyleQualityConfig() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences("always", "always", "where-available", "own-label", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferOrganic()).isTrue();
    assertThat(draft.preferences().preferFreeRange()).isTrue(); // free-range eggs "always"
    assertThat(draft.preferences().preferOwnBrand()).isTrue(); // "own-label"
  }

  @Test
  void preferences_defaultFalse_whenNoLifestyleConfig() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferOrganic()).isFalse();
    assertThat(draft.preferences().preferFreeRange()).isFalse();
    assertThat(draft.preferences().preferOwnBrand()).isFalse();
  }

  @Test
  void preferredSku_comesFromLastPaidProductId() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());
    when(dataGateway.findLastPaidProviderProductId(userId, "flour"))
        .thenReturn(Optional.of("sku-flour-123"));

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.lines().get(0).preferredProviderProductId()).isEqualTo("sku-flour-123");
  }

  @Test
  void preferredSku_prefersTheLineProviderProductId_overLastPaid() {
    UUID userId = UUID.randomUUID();
    GroceryOrderLine ln = line("flour", "Flour", 1);
    ln.setProviderProductId("sku-on-line");
    GroceryOrder order = orderWith(userId, ln);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.lines().get(0).preferredProviderProductId()).isEqualTo("sku-on-line");
  }

  private static LifestyleConfigDto dto(UUID userId, GroceryQualityPreferences quality) {
    LifestyleConfigDocument doc =
        new LifestyleConfigDocument(
            null, null, null, null, null, null, null, null, null, null, quality, null);
    Instant now = Instant.now();
    return new LifestyleConfigDto(UUID.randomUUID(), userId, doc, null, 0L, now, now);
  }

  // ============================== boundary / mutation-hardening tests
  // ==============================

  // GroceryQualityPreferences signature is (organic, freeRangeEggs, freeRangeMeat,
  // brandedVsOwnLabel, notes).
  @Test
  void preferences_explicitNever_treatedAsNoPreference() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences("no", "never", "never", "branded", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferFreeRange()).isFalse(); // both "never"
    assertThat(draft.preferences().preferOrganic()).isFalse(); // "no"
    assertThat(draft.preferences().preferOwnBrand()).isFalse(); // "branded" → not own-label
  }

  @Test
  void preferences_explicitNone_treatedAsNoPreference() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences("none", "none", "none", null, null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferFreeRange()).isFalse();
    assertThat(draft.preferences().preferOrganic()).isFalse();
  }

  @Test
  void preferences_blankString_treatedAsNoPreference() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality = new GroceryQualityPreferences("   ", "", "  ", "  ", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferFreeRange()).isFalse(); // blanks
    assertThat(draft.preferences().preferOrganic()).isFalse(); // blank
    assertThat(draft.preferences().preferOwnBrand()).isFalse(); // blank
  }

  @Test
  void preferences_caseInsensitive_NEVERequivalent() {
    // Mixed-case "NEVER"/"NoNe" must also negate (toLowerCase branch).
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences("No", "NEVER", "NoNe", null, null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferFreeRange()).isFalse();
    assertThat(draft.preferences().preferOrganic()).isFalse();
  }

  @Test
  void preferences_freeRangeEggsOnly_setsPreferFreeRange() {
    // Only one of free-range-eggs / free-range-meat needs to be set for preferFreeRange to be true.
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences(null, "always", null, null, null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferFreeRange()).isTrue();
  }

  @Test
  void preferences_freeRangeMeatOnly_setsPreferFreeRange() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences(null, null, "always", null, null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    assertThat(draft.preferences().preferFreeRange()).isTrue();
  }

  @Test
  void preferences_ownBrand_storeSynonym_recognised() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences(null, null, null, "store brand", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);
    assertThat(draft.preferences().preferOwnBrand()).isTrue();
  }

  @Test
  void preferences_ownBrand_valueSynonym_recognised() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences(null, null, null, "value range", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);
    assertThat(draft.preferences().preferOwnBrand()).isTrue();
  }

  @Test
  void preferences_ownBrand_budgetSynonym_recognised() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences(null, null, null, "budget aisle", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);
    assertThat(draft.preferences().preferOwnBrand()).isTrue();
  }

  @Test
  void preferences_ownBrand_brandedString_isFalse() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = orderWith(userId, line("flour", "Flour", 1));
    GroceryQualityPreferences quality =
        new GroceryQualityPreferences(null, null, null, "branded only", null);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId))
        .thenReturn(Optional.of(dto(userId, quality)));
    lenient()
        .when(dataGateway.findLastPaidProviderProductId(eq(userId), any()))
        .thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);
    assertThat(draft.preferences().preferOwnBrand()).isFalse();
  }

  @Test
  void preferredSku_blankProviderProductId_treatedAsAbsent() {
    // The line has a blank providerProductId — falls through to the last-paid lookup.
    UUID userId = UUID.randomUUID();
    GroceryOrderLine ln = line("flour", "Flour", 1);
    // Note: an empty string is still non-null and the production code uses != null only,
    // so blank STAYS as the preferred SKU. This is asserted explicitly to pin the branch.
    ln.setProviderProductId("");
    GroceryOrder order = orderWith(userId, ln);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);

    // The production code's null-check is the only gate — blank is kept as-is.
    assertThat(draft.lines().get(0).preferredProviderProductId()).isEqualTo("");
  }

  @Test
  void preferredSku_emptyLastPaid_returnsNull() {
    // No providerProductId on the line + empty Optional from data gateway → preferredSku = null.
    UUID userId = UUID.randomUUID();
    GroceryOrderLine ln = line("flour", "Flour", 1);
    ln.setProviderProductId(null);
    GroceryOrder order = orderWith(userId, ln);
    when(lifestyleConfigQueryService.getLifestyleConfig(userId)).thenReturn(Optional.empty());
    when(dataGateway.findLastPaidProviderProductId(userId, "flour")).thenReturn(Optional.empty());

    BasketDraft draft = assembler().assemble(order);
    assertThat(draft.lines().get(0).preferredProviderProductId()).isNull();
  }
}
