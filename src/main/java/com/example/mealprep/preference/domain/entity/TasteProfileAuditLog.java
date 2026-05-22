package com.example.mealprep.preference.domain.entity;

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

/**
 * Append-only audit row for a single change on a {@link TasteProfile}. One row per write — manual
 * override, AI delta apply (deferred), refresh trigger, init, or rollback (deferred).
 *
 * <p>Distinct from {@link TasteProfileVersion} — this table is the change <i>provenance</i> log
 * (who, when, what kind of change); the versions table holds the document snapshot. They have
 * different read patterns and different retention concerns.
 *
 * <p>No {@code @Version}, no {@code @LastModifiedDate} — append-only.
 */
@Entity
@Table(name = "preference_taste_profile_audit")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TasteProfileAuditLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "taste_profile_id", nullable = false, updatable = false)
  private TasteProfile tasteProfile;

  @Column(name = "actor_user_id", nullable = false, updatable = false)
  private UUID actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
  private ActorType actorType;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", nullable = false, length = 32, updatable = false)
  private TasteProfileChangeType changeType;

  @Column(name = "previous_document_version", updatable = false)
  private Integer previousDocumentVersion;

  @Column(name = "new_document_version", nullable = false, updatable = false)
  private int newDocumentVersion;

  @Column(name = "summary", length = 512, updatable = false)
  private String summary;

  @Column(name = "trace_id", updatable = false)
  private UUID traceId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;
}
