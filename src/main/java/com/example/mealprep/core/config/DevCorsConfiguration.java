package com.example.mealprep.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dev-profile CORS configuration permitting the Vite dev server ({@code http://localhost:5173}) to
 * call the REST API.
 *
 * <p><b>Profile safety:</b> the entire class is gated to the {@code dev} Spring profile via {@link
 * Profile @Profile("dev")}. Under {@code prod} and {@code test} profiles the bean is absent from
 * the context, so the default Spring posture (reject cross-origin requests) applies. This is
 * critical: an {@code allowCredentials=true} CORS bean reachable from any other profile would
 * defeat the httpOnly-cookie + CSRF posture established by {@code auth-01a}.
 *
 * <p>Per the LLD convention, cross-cutting concerns like CORS live in {@code core/config} rather
 * than on individual controllers via {@code @CrossOrigin} — the {@code ModuleBoundaryTest} ArchUnit
 * rule enforces that.
 *
 * <p>See {@code design/technical-architecture.md} §Frontend Topology and {@code
 * design/audits/2026-05-21-frontend-readiness-roadmap.md} A1 for the originating spec.
 */
@Configuration
@Profile("dev")
@EnableConfigurationProperties(CorsProperties.class)
public class DevCorsConfiguration {

  /**
   * The explicit method allowlist. Listed explicitly rather than via {@code "*"} so the contract is
   * grep-able and the LLD-style convention (no wildcards even in dev) is observed.
   */
  private static final String[] ALLOWED_METHODS = {
    "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"
  };

  /**
   * The explicit request-header allowlist.
   *
   * <ul>
   *   <li>{@code Content-Type}, {@code Accept} — JSON request/response negotiation.
   *   <li>{@code Authorization} — forward-compat; auth today is session-cookie based but a Bearer
   *       flow may land later.
   *   <li>{@code X-Origin}, {@code X-Origin-Trace}, {@code X-Origin-Depth} — origin-tracking
   *       headers landing in {@code core/02b}; pre-allowed here so {@code 02b} need not revisit
   *       CORS.
   *   <li>{@code X-Trace-Id} — decision-log trace propagation from {@code core-01}.
   *   <li>{@code X-CSRF-TOKEN} — Spring Security's default CSRF header; pre-allowed for the Vite
   *       CSRF flow that lands later.
   * </ul>
   */
  private static final String[] ALLOWED_HEADERS = {
    "Content-Type",
    "Accept",
    "Authorization",
    "X-Origin",
    "X-Origin-Trace",
    "X-Origin-Depth",
    "X-Trace-Id",
    "X-CSRF-TOKEN"
  };

  /**
   * Response headers the browser is allowed to expose to JS. {@code X-Trace-Id} powers support-link
   * telemetry on the frontend; {@code Location} is read for create-then-redirect flows; {@code
   * Content-Disposition} for download endpoints.
   */
  private static final String[] EXPOSED_HEADERS = {"X-Trace-Id", "Location", "Content-Disposition"};

  /**
   * Single bean: a {@link WebMvcConfigurer} that registers the CORS mapping. Implemented as a
   * lambda capturing the configured origin and preflight max-age from {@link CorsProperties}.
   */
  @Bean
  public WebMvcConfigurer devCorsWebMvcConfigurer(CorsProperties corsProperties) {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry
            .addMapping("/**")
            .allowedOrigins(corsProperties.allowedOrigin())
            .allowedMethods(ALLOWED_METHODS)
            .allowedHeaders(ALLOWED_HEADERS)
            .exposedHeaders(EXPOSED_HEADERS)
            .allowCredentials(true)
            .maxAge(corsProperties.preflightMaxAge().toSeconds());
      }
    };
  }
}
