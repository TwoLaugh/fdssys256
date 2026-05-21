package com.example.mealprep.core.api;

/**
 * Canonical names of the origin-tracking HTTP headers. Imported from this single class wherever an
 * inbound or outbound HTTP call needs to set or read these headers — never typed as string literals
 * elsewhere in production code.
 *
 * <p>Defined in {@code core.api} (cross-module re-use, no module-specific concerns) per {@code
 * lld/style-guide.md}.
 *
 * @see com.example.mealprep.core.origin.OriginFilter
 * @see <a href="../../../../../../../../../design/origin-tracking-pattern.md">design doc</a>
 */
public final class OriginHeaders {

  /** Origin kind of the request — see {@link com.example.mealprep.core.origin.Origin}. */
  public static final String X_ORIGIN = "X-Origin";

  /** Free-form trace string linking the request back to the originating event / job instance. */
  public static final String X_ORIGIN_TRACE = "X-Origin-Trace";

  /** Recursion-guard depth (default 0). The filter rejects > 3 with 422. */
  public static final String X_ORIGIN_DEPTH = "X-Origin-Depth";

  /**
   * Pattern-B "act as user" header — required when authentication is via Bearer service token (the
   * token authenticates the system caller; this header identifies the user the call is for).
   */
  public static final String X_ACTING_AS = "X-Acting-As";

  private OriginHeaders() {
    // utility — no instances.
  }
}
