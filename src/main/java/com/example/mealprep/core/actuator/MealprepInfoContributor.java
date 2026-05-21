package com.example.mealprep.core.actuator;

import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Guaranteed-non-empty content for the {@code /actuator/info} endpoint.
 *
 * <p>Spring Boot 3.x's {@code InfoEndpoint} returns HTTP 404 when no {@link InfoContributor}
 * produces any details (see {@code InfoEndpoint#info()}: returns {@code null} → 404 when the {@code
 * Info.Details} map is empty). Relying on auto-configured contributors (build, java, env reading
 * {@code info.*} properties) is fragile: surefire/failsafe test runs may not have {@code
 * META-INF/build-info.properties} generated, {@code JavaInfoContributor} sometimes emits nothing in
 * test contexts, and the env contributor's gating has bitten us before.
 *
 * <p>This contributor unconditionally adds {@code {"app":{"name":"mealprep"}}} so the endpoint
 * always returns 200. The security-critical property (no {@code env} block leaking env vars in the
 * response) is preserved — this contributor only adds the {@code app} branch.
 */
@Component
public class MealprepInfoContributor implements InfoContributor {

  @Override
  public void contribute(Info.Builder builder) {
    builder.withDetail("app", Map.of("name", "mealprep"));
  }
}
