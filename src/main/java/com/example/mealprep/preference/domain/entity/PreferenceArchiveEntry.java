package com.example.mealprep.preference.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * A single item pruned from the taste profile into the unbounded archive. Per {@code
 * lld/preference.md:175-191} and {@code tickets/preference/01e-preference-archive.md}.
 *
 * <p><b>Append-only-with-a-single-mutable-column.</b> Every field is {@code updatable = false}
 * except {@link #rePromotedAt}. A new row is inserted on each archive operation (no upsert); when
 * an item re-emerges, {@code rePromotedAt} is flipped on the most-recent unpromoted row and that
 * row remains in the table as history. No {@code @Version}: the only writer is the future {@code
 * TasteProfileDeltaApplier}, which runs under the same single-flight-per-user transactional
 * boundary as the taste-profile update, so no concurrent {@code RE_PROMOTE} for the same item can
 * occur.
 *
 * <p>{@link #itemKey} is the dedup identity of the item within its {@link #fieldPath} — {@code
 * item} for an {@code IngredientPreference}, {@code name} for a {@code RecipeRecommendation},
 * {@code hypothesis} for an {@code ActiveExperiment}. {@link #itemPayload} carries the full JSON
 * shape, restored verbatim on re-promotion.
 */
@Entity
@Table(name = "preference_taste_profile_archive")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PreferenceArchiveEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "field_path", length = 128, nullable = false, updatable = false)
  private String fieldPath;

  @Column(name = "item_key", length = 128, nullable = false, updatable = false)
  private String itemKey;

  @Type(JsonBinaryType.class)
  @Column(name = "item_payload", nullable = false, columnDefinition = "jsonb", updatable = false)
  private JsonNode itemPayload;

  @Column(name = "evidence_count", nullable = false, updatable = false)
  private int evidenceCount;

  @Column(name = "last_signal_at", updatable = false)
  private LocalDate lastSignalAt;

  @Column(name = "archived_at", nullable = false, updatable = false)
  private Instant archivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "archived_reason", length = 32, nullable = false, updatable = false)
  private ArchiveReason archivedReason;

  /** The only mutable column — non-null once the item has been re-promoted to the live profile. */
  @Column(name = "re_promoted_at")
  private Instant rePromotedAt;
}
