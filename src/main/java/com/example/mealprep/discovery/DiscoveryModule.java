package com.example.mealprep.discovery;

import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import org.springframework.stereotype.Component;

/**
 * Module facade re-exporting the discovery module's public service interfaces. Cross-module callers
 * (planner cold-start, recipe user-initiated) inject this rather than reaching into {@code
 * domain.service.*} directly.
 *
 * <p>Mirrors {@code RecipeModule} / {@code AiModule} / {@code PreferenceModule}: thin, no business
 * logic, just bean references. 01a wires it against an empty-interface stub ({@link
 * com.example.mealprep.discovery.domain.service.internal.DiscoveryServiceStub}); 01b appends
 * methods to the interfaces + replaces the stub with {@code DiscoveryServiceImpl}.
 */
@Component
public class DiscoveryModule {

  private final DiscoveryService discoveryService;
  private final DiscoveryQueryService discoveryQueryService;

  public DiscoveryModule(
      DiscoveryService discoveryService, DiscoveryQueryService discoveryQueryService) {
    this.discoveryService = discoveryService;
    this.discoveryQueryService = discoveryQueryService;
  }

  public DiscoveryService discoveryService() {
    return discoveryService;
  }

  public DiscoveryQueryService discoveryQueryService() {
    return discoveryQueryService;
  }
}
