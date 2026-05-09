package com.example.mealprep.recipe.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Child of {@link RecipeVersion}. {@code (version_id, step_number)} is unique at the DB level. */
@Entity
@Table(name = "recipe_method_steps")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeMethodStep {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "version_id", nullable = false)
  private RecipeVersion version;

  @Column(name = "step_number", nullable = false)
  private int stepNumber;

  @Column(name = "instruction", nullable = false)
  private String instruction;

  @Column(name = "duration_minutes")
  private Integer durationMinutes;
}
