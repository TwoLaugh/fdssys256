package com.example.mealprep.preference.domain.entity;

/**
 * Reason a taste-profile item was pruned into the archive. Stored as {@code varchar(32)} via
 * {@code @Enumerated(STRING)}. Values verbatim from {@code lld/preference.md:258}.
 *
 * <ul>
 *   <li>{@link #LOW_EVIDENCE} — the item never accumulated enough supporting feedback signals.
 *   <li>{@link #STALE} — the item's {@code last_signal} aged past the relevance window.
 *   <li>{@link #TOKEN_PRESSURE} — the document exceeded the HLD's 2500-token budget and the item
 *       was evicted to stay within budget.
 * </ul>
 *
 * <p>Declared here in 01e (the hard-constraints ticket 01a explicitly deferred this enum to the
 * archive ticket).
 */
public enum ArchiveReason {
  LOW_EVIDENCE,
  STALE,
  TOKEN_PRESSURE
}
