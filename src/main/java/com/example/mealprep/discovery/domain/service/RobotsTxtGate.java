package com.example.mealprep.discovery.domain.service;

import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import java.net.URI;

/**
 * Politeness gate that resolves the robots.txt policy for a candidate URL on behalf of a given
 * User-Agent. Per LLD lines 405-408.
 *
 * <p>v1 ships {@code InHouseRobotsTxtGate} (in {@code discovery.api.internal}, where the {@code
 * RestClient} dependency belongs); a future Crawler-Commons-backed impl can replace it through the
 * SPI-with-Noop pattern (a separate {@code chore:} ticket).
 */
public interface RobotsTxtGate {

  RobotsTxtOutcome check(URI candidateUrl, String userAgent);
}
