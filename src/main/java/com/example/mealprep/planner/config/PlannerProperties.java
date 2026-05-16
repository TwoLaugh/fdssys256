package com.example.mealprep.planner.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the planner module — bound to {@code mealprep.planner.*}. 01d
 * ships the first 7 keys consumed by the Stage-A beam search and hard-filter runner; subsequent
 * tickets (01e weight scheme, 01f rollup tunables, 01g/01h LLM tier targets) append further fields.
 *
 * <p>Spring Boot 3.x record-shaped {@code @ConfigurationProperties} are auto-{@code
 * ConstructorBinding} so defaults are wired via {@code application.properties} (not record
 * defaults). {@code @Validated} runs the Jakarta constraints at context-load time — a bad override
 * crashes startup with a clear bind-validation message.
 *
 * <p>{@code weekStartDayOfWeek} accepts Spring's relaxed binding ({@code MONDAY}, {@code monday},
 * {@code Monday}); the project locks to Monday-start weeks per the LLD.
 */
@ConfigurationProperties(prefix = "mealprep.planner")
@Validated
public record PlannerProperties(
    @NotNull DayOfWeek weekStartDayOfWeek,
    @Min(1) int beamWidth,
    @Min(1) int topN,
    @Min(1) int minPoolPerSlot,
    @Min(1) int maxPoolPerSlot,
    @NotNull @DecimalMin("1.0") @DecimalMax("3.0") BigDecimal maxTimeOvershootRatio,
    @NotNull Duration stageATimeout) {}
