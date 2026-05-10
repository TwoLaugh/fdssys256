package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.DailyActivityLog;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DailyActivityLog}. Read-by-(user,date) and (user,range);
 * upserts use {@code find + save}.
 */
public interface DailyActivityLogRepository extends JpaRepository<DailyActivityLog, UUID> {

  Optional<DailyActivityLog> findByUserIdAndOnDate(UUID userId, LocalDate onDate);

  List<DailyActivityLog> findByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);
}
