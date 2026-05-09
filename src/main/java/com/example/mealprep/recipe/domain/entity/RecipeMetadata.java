package com.example.mealprep.recipe.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

/**
 * One-to-one metadata for a {@link RecipeVersion}. {@code equipment_required} and {@code
 * meal_types} stored as jsonb list-of-strings (text[] workaround — same as preference / nutrition).
 */
@Entity
@Table(name = "recipe_metadata")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeMetadata {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "version_id", nullable = false, unique = true)
  private RecipeVersion version;

  @Column(name = "servings", nullable = false)
  private int servings;

  @Column(name = "prep_time_mins", nullable = false)
  private int prepTimeMins;

  @Column(name = "cook_time_mins", nullable = false)
  private int cookTimeMins;

  @Column(name = "total_time_mins", nullable = false)
  private int totalTimeMins;

  @Type(JsonBinaryType.class)
  @Column(name = "equipment_required", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> equipmentRequired = new ArrayList<>();

  @Column(name = "fridge_days")
  private Integer fridgeDays;

  @Column(name = "freezer_weeks")
  private Integer freezerWeeks;

  @Column(name = "packable", nullable = false)
  private boolean packable;

  @Column(name = "cuisine", length = 64)
  private String cuisine;

  @Type(JsonBinaryType.class)
  @Column(name = "meal_types", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> mealTypes = new ArrayList<>();
}
