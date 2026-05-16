package com.example.mealprep.planner;

import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Module facade — re-exports the planner module's public service interfaces. Cross-module callers
 * inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>01a exposes only {@link PlanQueryService}; the write surface ({@code PlannerService}) lands
 * with 01j and will be wired in here at that point.
 *
 * <p>{@code @EnableConfigurationProperties(PlannerProperties.class)} binds the {@code
 * mealprep.planner.*} keys consumed by the Stage-A beam search (01d ships the first 7 fields).
 */
@Component
@EnableConfigurationProperties(PlannerProperties.class)
public class PlannerModule {

  private final PlanQueryService planQueryService;

  public PlannerModule(PlanQueryService planQueryService) {
    this.planQueryService = planQueryService;
  }

  public PlanQueryService query() {
    return planQueryService;
  }
}
