package com.example.mealprep.provisions.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Cook-event idempotency row. Inserted at the start of {@code applyCookEvent}; the unique key
 * {@code (meal_slot_id, dedupe_key)} fences duplicate {@code MealCookedEvent} replays. Rows older
 * than 24h are swept by {@link
 * com.example.mealprep.provisions.domain.service.internal.CookEventDedupeSweeper}.
 *
 * <p>See LLD line 620 / 623.
 */
@Entity
@Table(name = "provision_cook_event_dedupe")
public class ProvisionCookEventDedupe {

  @EmbeddedId private CookEventDedupeId id;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  /** For Hibernate. Not for application code. */
  protected ProvisionCookEventDedupe() {}

  public ProvisionCookEventDedupe(UUID mealSlotId, String dedupeKey, Instant createdAt) {
    this.id = new CookEventDedupeId(mealSlotId, dedupeKey);
    this.createdAt = createdAt;
  }

  public CookEventDedupeId getId() {
    return id;
  }

  public UUID getMealSlotId() {
    return id == null ? null : id.getMealSlotId();
  }

  public String getDedupeKey() {
    return id == null ? null : id.getDedupeKey();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Composite primary key for {@link ProvisionCookEventDedupe}. */
  @Embeddable
  public static class CookEventDedupeId implements Serializable {

    @Column(name = "meal_slot_id", nullable = false)
    private UUID mealSlotId;

    @Column(name = "dedupe_key", nullable = false, length = 64)
    private String dedupeKey;

    protected CookEventDedupeId() {}

    public CookEventDedupeId(UUID mealSlotId, String dedupeKey) {
      this.mealSlotId = mealSlotId;
      this.dedupeKey = dedupeKey;
    }

    public UUID getMealSlotId() {
      return mealSlotId;
    }

    public String getDedupeKey() {
      return dedupeKey;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CookEventDedupeId that)) return false;
      return Objects.equals(mealSlotId, that.mealSlotId)
          && Objects.equals(dedupeKey, that.dedupeKey);
    }

    @Override
    public int hashCode() {
      return Objects.hash(mealSlotId, dedupeKey);
    }
  }
}
