package com.example.mealprep.grocery.domain.service.internal;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraft;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraftLine;
import com.example.mealprep.grocery.domain.service.internal.providers.BasketDraftPreferences;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument.GroceryQualityPreferences;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link BasketDraft} from a {@link GroceryOrder} + the user's lifestyle quality
 * preferences (grocery-01e). Per lld/grocery.md line 20 / 662 and the edge-case checklist: lines
 * are 1:1 with the order lines; preferences derive from the lifestyle config's {@code
 * groceryQualityPreferences}; the preferred SKU per line is the last-paid {@code providerProductId}
 * for {@code (userId, ingredientMappingKey)}. Package-private internal plumbing.
 *
 * <p>The quality strings are free-form in the lifestyle config (e.g. {@code "always"} / {@code
 * "where-available"} / {@code "never"}); v1 treats any non-null, non-{@code "never"} value as the
 * boolean "prefer" flag — a deliberate simplification (full SKU-matching is the deferred Tesco
 * ticket's concern).
 */
@Component
class BasketDraftAssembler {

  private final LifestyleConfigQueryService lifestyleConfigQueryService;
  private final GroceryOrderDataGateway dataGateway;

  BasketDraftAssembler(
      LifestyleConfigQueryService lifestyleConfigQueryService,
      GroceryOrderDataGateway dataGateway) {
    this.lifestyleConfigQueryService = lifestyleConfigQueryService;
    this.dataGateway = dataGateway;
  }

  /** Assemble the draft for an order (lines must be initialised on the aggregate). */
  BasketDraft assemble(GroceryOrder order) {
    GroceryQualityPreferences quality =
        lifestyleConfigQueryService
            .getLifestyleConfig(order.getUserId())
            .map(LifestyleConfigDto::document)
            .map(LifestyleConfigDocument::groceryQualityPreferences)
            .orElse(null);

    List<BasketDraftLine> draftLines = new ArrayList<>();
    for (GroceryOrderLine line : order.getLines()) {
      String preferredSku =
          line.getProviderProductId() != null
              ? line.getProviderProductId()
              : dataGateway
                  .findLastPaidProviderProductId(order.getUserId(), line.getIngredientMappingKey())
                  .orElse(null);
      draftLines.add(
          new BasketDraftLine(
              line.getId(),
              line.getIngredientMappingKey(),
              line.getDisplayName(),
              line.getQuantityRequested(),
              line.getQuantityUnit(),
              line.getPackSizeG(),
              line.getPackCountRequested(),
              preferredSku));
    }

    return new BasketDraft(order.getId(), order.getUserId(), draftLines, preferences(quality));
  }

  /** Map the lifestyle quality strings onto the boolean preference flags. */
  private static BasketDraftPreferences preferences(GroceryQualityPreferences quality) {
    if (quality == null) {
      return new BasketDraftPreferences(false, false, false, null);
    }
    boolean preferOwnBrand = ownBrand(quality.brandedVsOwnLabel());
    boolean preferOrganic = prefer(quality.organic());
    boolean preferFreeRange = prefer(quality.freeRangeEggs()) || prefer(quality.freeRangeMeat());
    return new BasketDraftPreferences(preferOwnBrand, preferOrganic, preferFreeRange, null);
  }

  /** Any non-null value that is not an explicit "never" / "no" reads as "prefer it". */
  private static boolean prefer(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String v = value.trim().toLowerCase();
    return !(v.equals("never") || v.equals("no") || v.equals("none"));
  }

  /** Own-brand preference: the value mentions own-label / store / value, not a named brand. */
  private static boolean ownBrand(String brandedVsOwnLabel) {
    if (brandedVsOwnLabel == null || brandedVsOwnLabel.isBlank()) {
      return false;
    }
    String v = brandedVsOwnLabel.trim().toLowerCase();
    return v.contains("own") || v.contains("store") || v.contains("value") || v.contains("budget");
  }
}
