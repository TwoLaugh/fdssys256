package com.example.mealprep.grocery.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
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
 * Tier 3 aggregate root — a provider-proposed substitution awaiting user review. Per lld/grocery.md
 * §Entities line 364. {@code proposalStatus} enum; {@code rawPayload} mapped to {@link JsonNode}
 * (opaque diagnostic blob for {@code UNPARSED} cases). {@code @Version} guards the stale-resolve
 * race (concurrent resolve attempts → 409).
 *
 * <p>{@code groceryOrderId} / {@code groceryOrderLineId} are stored as plain UUID soft FKs (the
 * proposal is queried separately from the order aggregate, not loaded with it).
 */
@Entity
@Table(name = "grocery_substitution_proposals")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class GrocerySubstitutionProposal {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "grocery_order_id", nullable = false)
  private UUID groceryOrderId;

  @Column(name = "grocery_order_line_id")
  private UUID groceryOrderLineId;

  @Column(name = "original_product_id", nullable = false, length = 128)
  private String originalProductId;

  @Column(name = "original_display_name", nullable = false, length = 255)
  private String originalDisplayName;

  @Column(name = "original_ingredient_mapping_key", length = 128)
  private String originalIngredientMappingKey;

  @Column(name = "substitute_product_id", nullable = false, length = 128)
  private String substituteProductId;

  @Column(name = "substitute_display_name", nullable = false, length = 255)
  private String substituteDisplayName;

  @Column(name = "substitute_ingredient_mapping_key", length = 128)
  private String substituteIngredientMappingKey;

  @Column(name = "substitute_quantity", precision = 10, scale = 3)
  private BigDecimal substituteQuantity;

  @Column(name = "substitute_unit", length = 16)
  private String substituteUnit;

  @Column(name = "substitute_unit_pence")
  private Integer substituteUnitPence;

  @Column(name = "reason", length = 255)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(name = "proposal_status", nullable = false, length = 32)
  private SubstitutionProposalStatus proposalStatus;

  @Type(JsonBinaryType.class)
  @Column(name = "raw_payload", columnDefinition = "jsonb")
  private JsonNode rawPayload;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "resolved_by_user_id")
  private UUID resolvedByUserId;

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
