package com.example.mealprep.core;

import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.lock.LockService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the public service interfaces of the core module.
 *
 * <p>Other modules inject {@code CoreModule} (or its individual services) rather than reaching into
 * {@code core.audit.domain.service} or {@code core.lock} directly — keeps the cross-module
 * dependency surface explicit and grep-able from one file.
 *
 * <p>The facade carries no business logic; it is a thin wiring helper Spring resolves at startup.
 */
@Component
public class CoreModule {

  private final DecisionLogService decisionLogService;
  private final DecisionLogQueryService decisionLogQueryService;
  private final LockService lockService;

  public CoreModule(
      DecisionLogService decisionLogService,
      DecisionLogQueryService decisionLogQueryService,
      LockService lockService) {
    this.decisionLogService = decisionLogService;
    this.decisionLogQueryService = decisionLogQueryService;
    this.lockService = lockService;
  }

  public DecisionLogService decisionLog() {
    return decisionLogService;
  }

  public DecisionLogQueryService decisionLogQuery() {
    return decisionLogQueryService;
  }

  public LockService lock() {
    return lockService;
  }
}
