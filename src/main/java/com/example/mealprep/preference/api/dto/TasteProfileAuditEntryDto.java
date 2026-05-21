package com.example.mealprep.preference.api.dto;

import com.example.mealprep.preference.domain.entity.ActorType;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry in the taste profile audit log — change provenance only (who, when, what kind of
 * change). The full document-at-version lives in {@link TasteProfileVersionDto}.
 */
public record TasteProfileAuditEntryDto(
    UUID id,
    UUID actorUserId,
    ActorType actorType,
    TasteProfileChangeType changeType,
    Integer previousDocumentVersion,
    int newDocumentVersion,
    String summary,
    UUID traceId,
    Instant occurredAt) {}
