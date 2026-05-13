package com.example.mealprep.planner.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One day of the plan's nutritional / cost / time rollup. JSON-only carrier inside {@link
 * RollupSummaryDocument}; not a JPA entity. Populated by planner-01f ({@code RollupBuilder}). 01a
 * treats this as an opaque carrier — values are zero on fixture data.
 */
public record DailyRollupDocument(
    LocalDate date,
    int kcal,
    BigDecimal proteinG,
    BigDecimal fatG,
    BigDecimal carbsG,
    BigDecimal fibreG,
    BigDecimal costGbp,
    int totalTimeMin,
    List<String> violations) {}
