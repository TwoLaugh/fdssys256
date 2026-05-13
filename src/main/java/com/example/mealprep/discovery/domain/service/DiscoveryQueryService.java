package com.example.mealprep.discovery.domain.service;

/**
 * Public read-side facade for the discovery module. Injected by the (deferred) admin/debug
 * controller in 01b and by any cross-module consumer needing job/source/scrape-log reads.
 *
 * <p>01a ships the interface empty so {@link com.example.mealprep.discovery.DiscoveryModule} can
 * re-export it and downstream modules can compile against the package from day one. Method
 * signatures ({@code getJob}, {@code getJobForUser}, {@code listJobsForUser}, {@code getScrapeLog},
 * {@code listSources}, {@code getSource}) land in discovery-01b per LLD lines 358-365.
 */
public interface DiscoveryQueryService {}
