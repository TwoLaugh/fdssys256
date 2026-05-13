package com.example.mealprep.discovery.api.dto;

import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Inbound request shape for {@code POST /api/v1/discovery/jobs}. Per LLD lines 261-267.
 *
 * <p>The {@code @ValidDiscoveryConstraints} class-level validator is deferred to discovery-01b
 * (along with the controllers); for 01a we ship only {@code @Valid} on the constraints field so
 * field-level Jakarta annotations on the nested record run. The custom annotation re-attaches in
 * 01b — one-line churn.
 */
public record StartDiscoveryJobRequest(
    @NotNull DiscoveryJobTrigger trigger,
    @Min(1) @Max(50) int requestedCount,
    @NotNull @Valid DiscoveryConstraints constraints,
    @Nullable List<@NotBlank String> sourceKeys,
    @Nullable UUID traceId) {}
