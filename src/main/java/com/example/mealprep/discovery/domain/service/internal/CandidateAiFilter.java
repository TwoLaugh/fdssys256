package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import java.util.List;
import java.util.UUID;

/**
 * Internal helper interface for cheap candidate triage between {@code DiscoverySource.search} and
 * {@code DiscoverySource.fetchRecipe}. v1 ships a pass-through (see {@code
 * NoopCandidateAiFilterConfiguration}); when the prompt + AI integration land, the bean is replaced
 * via the SPI-with-Noop pattern with no caller change.
 *
 * <p>Per LLD lines 412-418. Lives in {@code domain.service.internal} so only the runner (also
 * package-internal in 01d) can inject it; cross-module callers don't see this interface.
 */
interface CandidateAiFilter {

  List<DiscoveryCandidate> filter(
      List<DiscoveryCandidate> candidates, DiscoveryConstraints constraints, UUID userId);
}
