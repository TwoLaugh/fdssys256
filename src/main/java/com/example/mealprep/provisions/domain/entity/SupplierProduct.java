package com.example.mealprep.provisions.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * Standalone aggregate root for a single supplier's SKU. One row per {@code (supplier, productId)}
 * — UNIQUE enforces this. Global reference data, NOT per-user — supplier pricing is shared across
 * all users.
 *
 * <p>The JSONB {@code substitution_history} column is mapped through {@link JsonBinaryType} from
 * hypersistence-utils-hibernate-63 as a {@code List<SubstitutionRecord>}. Append-only — never
 * queried by sub-field; read whole each time. JSONB-append concurrency is guarded by
 * {@code @Version} (two appenders collide → the loser retries with the refreshed version).
 */
@Entity
@Table(
    name = "provision_supplier_products",
    uniqueConstraints = @UniqueConstraint(columnNames = {"supplier", "product_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SupplierProduct {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "product_id", nullable = false, updatable = false, length = 128)
  private String productId;

  @Column(name = "supplier", nullable = false, updatable = false, length = 32)
  private String supplier;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "price", precision = 8, scale = 2)
  private BigDecimal price;

  @Column(name = "price_per_unit", precision = 8, scale = 4)
  private BigDecimal pricePerUnit;

  @Column(name = "unit", length = 16)
  private String unit;

  @Column(name = "pack_size_g")
  private Integer packSizeG;

  @Column(name = "pack_size_unit", length = 16)
  private String packSizeUnit;

  @Column(name = "category", length = 64)
  private String category;

  @Column(name = "clubcard_price", precision = 8, scale = 2)
  private BigDecimal clubcardPrice;

  @Column(name = "last_checked", nullable = false)
  private LocalDate lastChecked;

  @Type(JsonBinaryType.class)
  @Column(name = "substitution_history", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<SubstitutionRecord> substitutionHistory = List.of();

  @Column(name = "ingredient_mapping_key", length = 128)
  private String ingredientMappingKey;

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
