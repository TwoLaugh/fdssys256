package com.example.mealprep.provisions;

import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Module facade re-exporting the provisions module's public service interfaces. Cross-module
 * callers inject this (or an individual service) rather than reaching into {@code domain.service.*}
 * directly.
 *
 * <p>Mirrors {@code AuthModule} / {@code PreferenceModule} / {@code HouseholdModule} / {@code
 * CoreModule}; thin and carries no business logic. Partial in 01a — only the inventory-aggregate
 * query/update surface is wired; equipment/budget/supplier/waste/planner services land in
 * subsequent provisions sub-tickets.
 *
 * <p>01g promotes this to a {@code @Configuration} so {@code @EnableScheduling} activates the
 * cook-event dedupe sweep (LLD line 623). Spring still picks the class up as a bean.
 */
@Configuration
@EnableScheduling
public class ProvisionsModule {

  private final ProvisionQueryService provisionQueryService;
  private final ProvisionUpdateService provisionUpdateService;

  public ProvisionsModule(
      ProvisionQueryService provisionQueryService, ProvisionUpdateService provisionUpdateService) {
    this.provisionQueryService = provisionQueryService;
    this.provisionUpdateService = provisionUpdateService;
  }

  public ProvisionQueryService query() {
    return provisionQueryService;
  }

  public ProvisionUpdateService update() {
    return provisionUpdateService;
  }
}
