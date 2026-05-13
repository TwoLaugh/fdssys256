package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.Day;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Package-private repository for {@link Day}. Children of {@link
 * com.example.mealprep.planner.domain.entity.Plan} — queried via the aggregate, not directly. Empty
 * in 01a; future tickets may add direct queries if they prove useful (planner-01f reads days for
 * rollup; uses {@code Plan.getDays()} after the lazy-touch pattern).
 */
public interface DayRepository extends JpaRepository<Day, UUID> {}
