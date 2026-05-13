package com.example.mealprep.discovery.domain.service.internal;

import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import org.springframework.stereotype.Component;

/**
 * Placeholder bean implementing both {@link DiscoveryService} and {@link DiscoveryQueryService} so
 * that {@link com.example.mealprep.discovery.DiscoveryModule} injects cleanly at context-load time.
 *
 * <p>Deleted by discovery-01b when {@code DiscoveryServiceImpl} arrives with the real method
 * implementations. Ticket invariant 33 ("Decision: ship the stub").
 */
@Component
public class DiscoveryServiceStub implements DiscoveryService, DiscoveryQueryService {}
