package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.spi.Destination;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public-facing view of a {@code MisclassificationCorrection} row. The LLD doesn't fully spec this
 * shape (only the mapper signature, line 392); the record matches the entity's exposed fields, per
 * ticket 01a §17.
 */
public record MisclassificationCorrectionDto(
    UUID id,
    UUID feedbackEntryId,
    UUID originalRoutingId,
    Destination correctedDestination,
    Destination originalDestination,
    BigDecimal originalConfidence,
    String userCorrectionNote,
    UUID actorUserId,
    UUID replayRoutingId,
    CorrectionReplayStatus replayStatus,
    Instant occurredAt,
    Instant createdAt) {}
