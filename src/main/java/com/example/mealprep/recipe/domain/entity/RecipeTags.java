package com.example.mealprep.recipe.domain.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One-to-one tag set for a {@link RecipeVersion}. All fields are optional and accepted as user
 * input in 01a — AI inference defers to recipe-01k. {@code flavour_profile} and {@code
 * dietary_flags} use the jsonb list-of-strings workaround.
 */
@Entity
@Table(name = "recipe_tags")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeTags {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "version_id", nullable = false, unique = true)
  private RecipeVersion version;

  @Column(name = "protein", length = 64)
  private String protein;

  @Column(name = "cooking_method", length = 64)
  private String cookingMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "complexity", length = 24)
  private Complexity complexity;

  @Type(JsonBinaryType.class)
  @Column(name = "flavour_profile", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> flavourProfile = new ArrayList<>();

  @Type(JsonBinaryType.class)
  @Column(name = "dietary_flags", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> dietaryFlags = new ArrayList<>();
}
