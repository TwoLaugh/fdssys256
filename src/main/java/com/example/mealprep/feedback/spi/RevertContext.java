package com.example.mealprep.feedback.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Cross-module context handed to a {@code *FeedbackReverter} when the user corrects a misclassified
 * routing. Carries everything a destination module needs to locate and best-effort undo the write
 * it made for the original routing (per lld/feedback.md §Flow 4 step 3, ticket 01f §19).
 *
 * <p>{@code structuredPayload} / {@code destinationResultJson} are loose {@link JsonNode} — each
 * destination decides how to read its own correlation handle out of them. The revert is best-effort
 * and log-only in v1 until the wave-2 destinations grow their real revert methods.
 */
public record RevertContext(
    UUID originalRoutingId,
    UUID userId,
    UUID traceId,
    Destination originalDestination,
    JsonNode structuredPayload,
    JsonNode destinationResultJson) {}
