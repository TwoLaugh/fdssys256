package com.example.mealprep.planner.domain.service.internal.beamsearch;

import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;

/**
 * Stage-A search SPI. Implementation lives in {@link BeamSearchEngineImpl}; the composer (01j)
 * injects this interface. The interface is {@code public} because cross-module callers (the
 * composer, eventually a stand-alone Stage-A invocation) need to reference it — the impl is
 * package-private behind the {@code @Component} boundary.
 *
 * <p>Per LLD divergence noted in the 01d ticket, the return shape is {@link BeamSearchOutcome} (not
 * raw {@code List<CandidatePlan>}) so timeout-degradation is explicit at the type level.
 */
public interface BeamSearchEngine {

  BeamSearchOutcome search(PlanCompositionContext context, BeamSearchConfig config);
}
