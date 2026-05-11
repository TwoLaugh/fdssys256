package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.SafetyFindingDto;
import com.example.mealprep.nutrition.api.dto.SafetyGateVerdict;
import java.util.List;

/**
 * Outcome of {@link DirectiveSafetyGate#evaluate}. Verdict + findings list; persisted on the
 * directive row regardless of whether the verdict was {@code BLOCKED}.
 */
public record SafetyGateResult(SafetyGateVerdict verdict, List<SafetyFindingDto> findings) {

  public SafetyGateResult {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }
}
