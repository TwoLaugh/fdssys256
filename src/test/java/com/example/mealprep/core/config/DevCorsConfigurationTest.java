package com.example.mealprep.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Profile-gating + configuration-shape assertions for {@link DevCorsConfiguration}.
 *
 * <p>Three scenarios:
 *
 * <ol>
 *   <li>{@code dev} profile active → the {@code DevCorsConfiguration} bean (and its {@link
 *       WebMvcConfigurer}) is present, and the configured CORS mapping carries the expected origin,
 *       methods, headers, exposed-headers, credentials flag, and max-age.
 *   <li>{@code prod} profile active → the {@code DevCorsConfiguration} bean is <b>absent</b>. This
 *       is the critical safety invariant: a permissive CORS bean must not bleed into prod.
 *   <li>{@code test} profile active → same as prod (bean absent).
 * </ol>
 *
 * <p>Uses {@link ApplicationContextRunner} so the test does not spin up a full Spring Boot context
 * (no Docker, no Postgres) — we are exercising the {@code @Profile("dev")} gate and the {@code
 * CorsRegistry} shape, both of which are pure Spring container concerns.
 */
class DevCorsConfigurationTest {

  /** Property values mirroring {@code application-dev.properties}. */
  private static final String[] DEV_PROPS = {
    "mealprep.cors.allowed-origin=http://localhost:5173", "mealprep.cors.preflight-max-age=PT1H"
  };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(DevCorsConfiguration.class);

  @Test
  void dev_profile_registers_cors_bean_with_expected_shape() {
    contextRunner
        .withPropertyValues(DEV_PROPS)
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(DevCorsConfiguration.class);

              Map<String, WebMvcConfigurer> configurers =
                  ctx.getBeansOfType(WebMvcConfigurer.class);
              assertThat(configurers)
                  .as("dev profile must contribute exactly one CORS WebMvcConfigurer")
                  .containsKey("devCorsWebMvcConfigurer");

              WebMvcConfigurer configurer = configurers.get("devCorsWebMvcConfigurer");
              CorsConfiguration corsConfig = extractCorsConfig(configurer);

              assertThat(corsConfig.getAllowedOrigins())
                  .as("explicit origin — never a wildcard (allowCredentials=true forbids it)")
                  .containsExactly("http://localhost:5173");
              assertThat(corsConfig.getAllowedOrigins()).doesNotContain("*");

              assertThat(corsConfig.getAllowedMethods())
                  .as("explicit method allowlist — never a wildcard")
                  .containsExactlyInAnyOrder(
                      "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");

              assertThat(corsConfig.getAllowedHeaders())
                  .as(
                      "explicit header allowlist; includes forward-compat X-Origin*, X-CSRF-TOKEN,"
                          + " Authorization")
                  .containsExactlyInAnyOrder(
                      "Content-Type",
                      "Accept",
                      "Authorization",
                      "X-Origin",
                      "X-Origin-Trace",
                      "X-Origin-Depth",
                      "X-Trace-Id",
                      "X-CSRF-TOKEN");
              assertThat(corsConfig.getAllowedHeaders()).doesNotContain("*");

              assertThat(corsConfig.getExposedHeaders())
                  .containsExactlyInAnyOrder("X-Trace-Id", "Location", "Content-Disposition");

              assertThat(corsConfig.getAllowCredentials()).isTrue();
              assertThat(corsConfig.getMaxAge()).isEqualTo(3600L);
            });
  }

  @Test
  void prod_profile_does_not_register_cors_bean() {
    contextRunner
        .withPropertyValues(DEV_PROPS) // even with the props set, prod must NOT register the bean
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx)
                  .as(
                      "critical safety invariant: DevCorsConfiguration must NOT activate in prod"
                          + " — would defeat httpOnly cookie + CSRF posture")
                  .doesNotHaveBean(DevCorsConfiguration.class);
              // No CORS bean of any name should be present from this configuration class.
              Map<String, WebMvcConfigurer> configurers =
                  ctx.getBeansOfType(WebMvcConfigurer.class);
              assertThat(configurers).doesNotContainKey("devCorsWebMvcConfigurer");
            });
  }

  @Test
  void test_profile_does_not_register_cors_bean() {
    contextRunner
        .withPropertyValues(DEV_PROPS)
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx)
                  .as("DevCorsConfiguration must NOT activate in test profile either")
                  .doesNotHaveBean(DevCorsConfiguration.class);
              Map<String, WebMvcConfigurer> configurers =
                  ctx.getBeansOfType(WebMvcConfigurer.class);
              assertThat(configurers).doesNotContainKey("devCorsWebMvcConfigurer");
            });
  }

  @Test
  void default_profile_does_not_register_cors_bean() {
    // No profile set at all → the @Profile("dev") gate excludes the bean. Belt-and-braces beyond
    // the explicit prod/test cases above.
    contextRunner
        .withPropertyValues(DEV_PROPS)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(DevCorsConfiguration.class);
            });
  }

  @Test
  void allowed_origin_property_override_is_authoritative() {
    contextRunner
        .withPropertyValues("mealprep.cors.allowed-origin=http://localhost:5174")
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              WebMvcConfigurer configurer =
                  ctx.getBean("devCorsWebMvcConfigurer", WebMvcConfigurer.class);
              CorsConfiguration corsConfig = extractCorsConfig(configurer);
              assertThat(corsConfig.getAllowedOrigins())
                  .as("property override must flow through to the CorsRegistry mapping")
                  .containsExactly("http://localhost:5174");
            });
  }

  /**
   * Drives the {@link WebMvcConfigurer#addCorsMappings(CorsRegistry)} contract against a test
   * subclass of {@link CorsRegistry} that exposes the protected {@code getCorsConfigurations()}
   * method, then asserts on the resulting {@link CorsConfiguration} for the {@code /**} mapping.
   */
  private static CorsConfiguration extractCorsConfig(WebMvcConfigurer configurer) {
    InspectableCorsRegistry registry = new InspectableCorsRegistry();
    configurer.addCorsMappings(registry);
    Map<String, CorsConfiguration> configs = registry.exposeCorsConfigurations();
    assertThat(configs.keySet())
        .as("DevCorsConfiguration must register exactly one /** mapping")
        .isEqualTo(Set.of("/**"));
    return configs.get("/**");
  }

  /**
   * Test-only subclass widening {@link CorsRegistry#getCorsConfigurations()} so the assertions can
   * read back what {@link DevCorsConfiguration} configured.
   */
  private static final class InspectableCorsRegistry extends CorsRegistry {
    Map<String, CorsConfiguration> exposeCorsConfigurations() {
      return getCorsConfigurations();
    }
  }
}
