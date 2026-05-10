package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.api.dto.IntakeAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the nutrition module's targets aggregate. Cross-module callers (planner, floor-gate,
 * intake aggregation in later sub-tickets) consume this to retrieve a user's targets — partial in
 * 01a (targets only), extended in 01b with the intake + daily-activity reads.
 */
public interface NutritionQueryService {

  /**
   * Read-by-others contract: return the calling user's nutrition targets, or empty if no row exists
   * yet. The returned DTO includes all four child collections (per-meal distribution,
   * micro-targets, eating window, activity adjustments) eagerly populated.
   */
  Optional<TargetsDto> getTargets(UUID userId);

  /** Paginated audit log of changes to the user's targets, newest-first. */
  Page<NutritionTargetsAuditEntryDto> getTargetsAuditLog(UUID userId, Pageable pageable);

  /**
   * Read the user's intake for a single day. Returns empty if no day row exists; the day does NOT
   * auto-create on read.
   */
  Optional<IntakeDayDto> getIntakeForDay(UUID userId, LocalDate onDate);

  /**
   * Range read of intake days in {@code [from, to]} inclusive, max 35 days. Days that don't exist
   * are not represented in the result. Throws {@code InvalidIntakeRangeException} if the range is
   * invalid.
   */
  List<IntakeDayDto> getIntakeRange(UUID userId, LocalDate from, LocalDate to);

  /**
   * Paginated audit log for an intake day, newest-first. Returns an empty page if the day row does
   * not exist (consistent with targets-audit semantics).
   */
  Page<IntakeAuditEntryDto> getIntakeAuditLog(UUID userId, LocalDate onDate, Pageable pageable);

  /** Read the user's daily activity entry for a single date, or empty if not logged. */
  Optional<DailyActivityDto> getDailyActivity(UUID userId, LocalDate onDate);

  /** Range read of daily activity entries in {@code [from, to]} inclusive, max 35 days. */
  List<DailyActivityDto> getDailyActivityRange(UUID userId, LocalDate from, LocalDate to);
}
