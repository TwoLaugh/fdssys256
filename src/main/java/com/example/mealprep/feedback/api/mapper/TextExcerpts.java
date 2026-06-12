package com.example.mealprep.feedback.api.mapper;

import com.example.mealprep.feedback.domain.entity.FeedbackEntry;

/**
 * Shared excerpt rule for the inbox/log DTOs (frontend-gaps: feedback-clarification-text-excerpt):
 * the leading 160 Unicode code points of the originating entry's {@code text}, plain-truncated — no
 * ellipsis marker, pinned so the value always satisfies the OpenAPI {@code maxLength: 160}.
 *
 * <p>The substring is code-point aware (never splits a surrogate pair); grapheme clusters (e.g. ZWJ
 * emoji sequences) may still be cut mid-cluster — accepted for v1, per the ticket. A missing parent
 * entry (shouldn't happen — the FK is NOT NULL) degrades to an empty string rather than a 500. The
 * full text stays available via {@code GET /feedback/{feedbackEntryId}}; the excerpt is display
 * context, not a replacement.
 */
final class TextExcerpts {

  static final int MAX_CODE_POINTS = 160;

  private TextExcerpts() {}

  static String fromEntry(FeedbackEntry entry) {
    if (entry == null || entry.getText() == null) {
      return "";
    }
    String text = entry.getText();
    if (text.codePointCount(0, text.length()) <= MAX_CODE_POINTS) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, MAX_CODE_POINTS));
  }
}
