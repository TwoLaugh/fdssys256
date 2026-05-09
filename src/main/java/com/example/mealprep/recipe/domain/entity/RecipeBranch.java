package com.example.mealprep.recipe.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

/**
 * A branch of a recipe's version history. 01a only ever creates the auto-generated {@code 'main'}
 * branch (one per recipe); user-facing branch creation lands in recipe-01d.
 *
 * <p>The branch is internal-only in 01a — the API doesn't expose it; tests assert its existence via
 * {@code JdbcTemplate}. The {@code branches[]} field on {@code RecipeDto} defers to recipe-01b
 * alongside the lookup endpoint.
 */
@Entity
@Table(
    name = "recipe_branches",
    uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id", "name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeBranch {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "recipe_id", nullable = false)
  private Recipe recipe;

  @Column(name = "parent_branch_id")
  private UUID parentBranchId;

  @Column(name = "branch_point_version_id")
  private UUID branchPointVersionId;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  @Column(name = "label", length = 120)
  private String label;

  @Column(name = "reason")
  private String reason;

  @Column(name = "current_version", nullable = false)
  private int currentVersion;

  @Column(name = "divergence_score", nullable = false, precision = 4, scale = 3)
  private BigDecimal divergenceScore;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "created_by_actor", nullable = false, length = 64)
  private String createdByActor;

  @Column(name = "adapter_trace_id")
  private UUID adapterTraceId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;
}
