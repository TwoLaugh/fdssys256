package com.example.mealprep.provisions.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Idempotency log row for the grocery-import flow (01h). One row per accepted {@code (userId,
 * source, sourceRef)} import — the composite PK enforces "no replays" at the database level (race-
 * safe via constraint, not in-memory).
 *
 * <p>Per LLD divergence note in 01h: the dedicated log table (rather than relying on the inventory-
 * side index) is required because substitution-only orders may produce zero inventory rows, leaving
 * the inventory-side index empty and unable to block a replay. The log table also carries {@code
 * processedAt} for observability + future retention-sweep targeting.
 *
 * <p>Append-only — no setters, no {@code @Version}.
 */
@Entity
@Table(name = "provision_grocery_import_log")
public class ProvisionGroceryImportLog {

  @EmbeddedId private GroceryImportLogId id;

  @Column(name = "trace_id")
  private UUID traceId;

  @Column(name = "processed_at", nullable = false, updatable = false)
  private Instant processedAt;

  /** For Hibernate. Not for application code. */
  protected ProvisionGroceryImportLog() {}

  public ProvisionGroceryImportLog(
      UUID userId, ItemSource source, String sourceRef, UUID traceId, Instant processedAt) {
    this.id = new GroceryImportLogId(userId, source, sourceRef);
    this.traceId = traceId;
    this.processedAt = processedAt;
  }

  public GroceryImportLogId getId() {
    return id;
  }

  public UUID getUserId() {
    return id == null ? null : id.getUserId();
  }

  public ItemSource getSource() {
    return id == null ? null : id.getSource();
  }

  public String getSourceRef() {
    return id == null ? null : id.getSourceRef();
  }

  public UUID getTraceId() {
    return traceId;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  /** Composite PK for the idempotency log. */
  @jakarta.persistence.Embeddable
  public static class GroceryImportLogId implements Serializable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private ItemSource source;

    @Column(name = "source_ref", nullable = false, updatable = false, length = 128)
    private String sourceRef;

    /** For Hibernate. Not for application code. */
    protected GroceryImportLogId() {}

    public GroceryImportLogId(UUID userId, ItemSource source, String sourceRef) {
      this.userId = userId;
      this.source = source;
      this.sourceRef = sourceRef;
    }

    public UUID getUserId() {
      return userId;
    }

    public ItemSource getSource() {
      return source;
    }

    public String getSourceRef() {
      return sourceRef;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof GroceryImportLogId that)) return false;
      return Objects.equals(userId, that.userId)
          && source == that.source
          && Objects.equals(sourceRef, that.sourceRef);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, source, sourceRef);
    }
  }
}
