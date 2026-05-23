package com.example.mealprep.preference.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import lombok.Setter;

/**
 * Child of {@link HardConstraints}. A non-allergy intolerance (lactose, FODMAPs, ...) the filter
 * treats as a hard rule, paired with a severity descriptor used by the planner's messaging.
 *
 * <p>{@code sourceDirectiveId} + {@code autoExpiresAt} (nutrition/01j) are NULL for user-authored
 * intolerances. They are stamped only on rows added by a temporary {@code preference_model} health
 * directive, so the deferred auto-expiry sweep ({@code
 * PreferenceUpdateService.removeTemporaryConstraint}) can reverse exactly the directive's
 * additions.
 */
@Entity
@Table(name = "preference_hard_intolerances")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class HardIntolerance {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "hard_constraints_id", nullable = false)
  private HardConstraints hardConstraints;

  @Column(name = "substance", nullable = false, length = 64)
  private String substance;

  @Column(name = "severity", nullable = false, length = 32)
  private String severity;

  @Column(name = "notes", length = 255)
  private String notes;

  /**
   * Source directive id for a directive-sourced temporary intolerance; {@code null} for
   * user-authored ones. Set together with {@link #autoExpiresAt} when a temporary {@code
   * preference_model} directive adds this row.
   */
  @Column(name = "source_directive_id")
  private UUID sourceDirectiveId;

  /** Expiry instant for a temporary directive-sourced intolerance; {@code null} otherwise. */
  @Column(name = "auto_expires_at")
  private Instant autoExpiresAt;
}
