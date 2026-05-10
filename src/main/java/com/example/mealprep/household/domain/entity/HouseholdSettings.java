package com.example.mealprep.household.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aggregate root for a household's settings document. Modelled as a standalone root rather than a
 * child of {@code Household} ({@code @OneToOne}) so the 01a entity graph does not need rewriting —
 * the LLD §Entities lists this as {@code @OneToOne}, but the 01a {@code Household} ships without
 * the back-reference; we link by {@code householdId} UNIQUE FK and let cascade-on-delete fall to
 * the DB.
 *
 * <p>The JSONB {@code document} column is mapped via {@link JsonBinaryType} from
 * hypersistence-utils-hibernate-63. {@code @Version} guards optimistic concurrency over the whole
 * document.
 */
@Entity
@Table(name = "household_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HouseholdSettings {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "household_id", nullable = false, updatable = false)
  private UUID householdId;

  @Type(JsonBinaryType.class)
  @Column(name = "document", nullable = false, columnDefinition = "jsonb")
  private HouseholdSettingsDocument document;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
