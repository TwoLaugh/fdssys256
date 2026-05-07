package com.example.mealprep.auth.domain.entity;

/**
 * Why a login attempt did not succeed. Recorded on each {@link LoginAttempt} row.
 *
 * <p>{@code BAD_PASSWORD} and {@code UNKNOWN_USER} are kept distinct in the audit log even though
 * the response is the same generic 401 — operators benefit from the distinction when investigating
 * spikes; it never reaches the client.
 *
 * <p>{@code THROTTLED} covers both per-username and per-IP rate-limit hits; {@code ACCOUNT_LOCKED}
 * covers post-5-failure user lockouts that return 423.
 */
public enum LoginFailureReason {
  BAD_PASSWORD,
  UNKNOWN_USER,
  ACCOUNT_LOCKED,
  THROTTLED,
  INVALID_REQUEST
}
