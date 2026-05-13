package com.example.mealprep.discovery.domain.service;

/**
 * Public update-side facade for the discovery module. Injected by {@code planner} (cold-start, via
 * {@code runJobSync}) and {@code recipe} (user-initiated / scheduled, via {@code startJob}).
 *
 * <p>01a ships the interface empty so {@link com.example.mealprep.discovery.DiscoveryModule} can
 * re-export it and downstream modules can compile against the package from day one. Method
 * signatures ({@code startJob}, {@code runJobSync}, {@code cancelJob}) land in discovery-01b per
 * LLD line 346.
 */
public interface DiscoveryService {}
