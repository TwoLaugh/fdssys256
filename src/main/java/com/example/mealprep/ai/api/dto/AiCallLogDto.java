package com.example.mealprep.ai.api.dto;

import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO for an audit-row read. The admin endpoints (01d) and IT assertions consume this; for
 * 01a it lives in {@code api/dto} so the mapper has a stable surface to compile against.
 */
public record AiCallLogDto(
    UUID id,
    UUID userId,
    UUID traceId,
    TaskType taskType,
    ModelTier modelTier,
    String modelId,
    String promptRefName,
    Integer promptRefVersion,
    Integer requestTokens,
    Integer responseTokens,
    long costMicroPence,
    CallStatus status,
    CallErrorKind errorKind,
    Integer latencyMs,
    Instant createdAt,
    Instant completedAt) {}
