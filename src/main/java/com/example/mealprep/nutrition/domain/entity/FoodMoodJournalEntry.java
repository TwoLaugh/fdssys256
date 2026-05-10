package com.example.mealprep.nutrition.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Standalone food/mood journal entry. One row per {@code (userId, onDate, mealSlot)} — {@code
 * mealSlot} is nullable so users can record untied entries; Postgres treats NULLs as not-equal in
 * the unique constraint, so multiple null-slot entries per {@code (userId, onDate)} are allowed.
 *
 * <p>Not a child of any other aggregate. {@code @Version} on the entity supports optimistic-locked
 * edits via the request DTO's {@code expectedVersion}.
 */
@Entity
@Table(
    name = "nutrition_food_mood_journal",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "on_date", "meal_slot"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class FoodMoodJournalEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "on_date", nullable = false, updatable = false)
  private LocalDate onDate;

  @Enumerated(EnumType.STRING)
  @Column(name = "meal_slot", length = 24)
  private MealSlot mealSlot;

  @Column(name = "journal_entry", nullable = false, columnDefinition = "text")
  private String journalEntry;

  @Column(name = "logged_at", nullable = false)
  private Instant loggedAt;

  @Version
  @Column(name = "optimistic_version", nullable = false)
  private long optimisticVersion;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
