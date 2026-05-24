package com.example.mealprep.e2e.support;

/**
 * Central, env-driven configuration for the E2E harness.
 *
 * <p>Every value resolves in the order: JVM system property → environment variable → default. The
 * Failsafe fork inherits the parent process environment, so CI (and a local run) only need to set
 * the env vars below; no Maven-side substitution is involved.
 *
 * <ul>
 *   <li>{@code MEALPREP_E2E_BASE_URL} / {@code mealprep.e2e.base-url} — base URL of the running app
 *       (default {@code http://localhost:8080}, the docker-compose published port).
 *   <li>{@code MEALPREP_E2E_CLEANUP} / {@code mealprep.e2e.cleanup} — {@code true} (default) =
 *       <b>clean mode</b> (the {@code @After} hook tears down per-scenario data); {@code false} =
 *       <b>soak mode</b> (state accumulates across scenarios). See {@link
 *       com.example.mealprep.e2e.steps.Hooks} and e2e/README.md.
 * </ul>
 */
public final class E2eConfig {

  private static final String DEFAULT_BASE_URL = "http://localhost:8080";

  private E2eConfig() {}

  /** Base URL of the running app under test. */
  public static String baseUrl() {
    return resolve("mealprep.e2e.base-url", "MEALPREP_E2E_BASE_URL", DEFAULT_BASE_URL);
  }

  /**
   * Whether the per-scenario cleanup hook should run. {@code true} = clean mode (default); {@code
   * false} = soak mode. Soak mode is also implied by anything other than a literal {@code "false"}
   * being absent — i.e. cleanup is ON unless explicitly disabled, so a forgotten flag never leaves
   * a regression run accumulating state.
   */
  public static boolean cleanupEnabled() {
    String raw = resolve("mealprep.e2e.cleanup", "MEALPREP_E2E_CLEANUP", "true");
    return !"false".equalsIgnoreCase(raw.trim());
  }

  private static String resolve(String sysProp, String envVar, String fallback) {
    String fromProp = System.getProperty(sysProp);
    if (fromProp != null && !fromProp.isBlank()) {
      return fromProp.trim();
    }
    String fromEnv = System.getenv(envVar);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    return fallback;
  }
}
