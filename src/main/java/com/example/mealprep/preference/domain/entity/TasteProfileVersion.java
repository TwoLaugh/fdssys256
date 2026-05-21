package com.example.mealprep.preference.domain.entity;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

/**
 * Per-delta-batch snapshot of a taste-profile document. Append-only: one row per applied delta
 * batch, manual override, or rollback. The {@link #documentSnapshot} captures the post-write state;
 * the {@link #deltasApplied} column stores the raw delta-array payload (parsed only when displayed
 * — per-delta typing happens at the consumer side via {@code TasteProfileDelta}).
 *
 * <p>Distinct from {@link TasteProfileAuditLog} — the audit log tracks <i>why</i> the profile
 * changed (who, when, which change type) while this table tracks <i>what</i> the document looked
 * like at each {@code documentVersion}. Both read patterns are supported by indices.
 *
 * <p>No {@code @Version} — append-only by design.
 */
@Entity
@Table(name = "preference_taste_profile_versions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TasteProfileVersion {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "taste_profile_id", nullable = false, updatable = false)
  private TasteProfile tasteProfile;

  @Column(name = "document_version", nullable = false, updatable = false)
  private int documentVersion;

  @Type(JsonBinaryType.class)
  @Column(
      name = "document_snapshot",
      nullable = false,
      updatable = false,
      columnDefinition = "jsonb")
  private TasteProfileDocument documentSnapshot;

  @Column(name = "feedback_range_start", length = 64, updatable = false)
  private String feedbackRangeStart;

  @Column(name = "feedback_range_end", length = 64, updatable = false)
  private String feedbackRangeEnd;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger", nullable = false, length = 16, updatable = false)
  private TasteProfileTrigger trigger;

  @Type(JsonBinaryType.class)
  @Column(name = "deltas_applied", nullable = false, updatable = false, columnDefinition = "jsonb")
  private JsonNode deltasApplied;

  @Column(name = "model_tier_used", nullable = false, length = 16, updatable = false)
  private String modelTierUsed;

  @Column(name = "generated_at", nullable = false, updatable = false)
  private Instant generatedAt;
}
