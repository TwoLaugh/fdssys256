package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.FoodMoodJournalEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link FoodMoodJournalEntry}. Per-day fetch is sorted ASC by {@code
 * loggedAt}; recent-entries pagination + the feedback-context loader sort DESC. The {@code top20}
 * derived query feeds the (future) Feedback System's classifier-context assembly.
 */
public interface FoodMoodJournalRepository extends JpaRepository<FoodMoodJournalEntry, UUID> {

  List<FoodMoodJournalEntry> findByUserIdAndOnDateOrderByLoggedAtAsc(UUID userId, LocalDate onDate);

  Page<FoodMoodJournalEntry> findByUserIdOrderByLoggedAtDesc(UUID userId, Pageable pageable);

  List<FoodMoodJournalEntry> findTop20ByUserIdOrderByLoggedAtDesc(UUID userId);
}
