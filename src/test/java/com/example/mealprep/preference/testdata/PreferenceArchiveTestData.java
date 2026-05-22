package com.example.mealprep.preference.testdata;

import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.PreferenceArchiveEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Test Data Builder for the preference archive. Defaults model a pruned {@code
 * IngredientPreference} living at {@code "ingredientPreferences.favourites"} with {@code item} as
 * its dedup key — the canonical re-emergence case.
 */
public final class PreferenceArchiveTestData {

  public static final String DEFAULT_FIELD_PATH = "ingredientPreferences.favourites";
  public static final String DEFAULT_ITEM_KEY = "chicken thighs";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private PreferenceArchiveTestData() {}

  /** A non-trivial {@code IngredientPreference}-shaped payload. */
  public static JsonNode ingredientPayload(String item, int evidenceCount) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("item", item);
    node.put("evidenceCount", evidenceCount);
    node.put("lastSignal", "2026-05-01");
    node.put("source", "FEEDBACK");
    return node;
  }

  /** A valid {@link ArchiveItemRequest} for the default ingredient case. */
  public static ArchiveItemRequest archiveRequest() {
    return new ArchiveItemRequest(
        DEFAULT_FIELD_PATH,
        DEFAULT_ITEM_KEY,
        ingredientPayload(DEFAULT_ITEM_KEY, 4),
        4,
        LocalDate.parse("2026-05-01"),
        ArchiveReason.LOW_EVIDENCE);
  }

  public static ArchiveItemRequest archiveRequest(
      String fieldPath, String itemKey, ArchiveReason reason) {
    return new ArchiveItemRequest(
        fieldPath,
        itemKey,
        ingredientPayload(itemKey, 4),
        4,
        LocalDate.parse("2026-05-01"),
        reason);
  }

  /** A persisted-shape archive entry (currently archived; {@code rePromotedAt == null}). */
  public static PreferenceArchiveEntry entry(UUID userId) {
    return PreferenceArchiveEntry.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .fieldPath(DEFAULT_FIELD_PATH)
        .itemKey(DEFAULT_ITEM_KEY)
        .itemPayload(ingredientPayload(DEFAULT_ITEM_KEY, 4))
        .evidenceCount(4)
        .lastSignalAt(LocalDate.parse("2026-05-01"))
        .archivedAt(Instant.parse("2026-05-10T10:00:00Z"))
        .archivedReason(ArchiveReason.LOW_EVIDENCE)
        .rePromotedAt(null)
        .build();
  }
}
