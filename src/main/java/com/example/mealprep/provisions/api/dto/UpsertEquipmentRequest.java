package com.example.mealprep.provisions.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/provisions/equipment/{name}}. The {@code name} is taken from
 * the path (validated against {@code ^[a-z0-9_]+$} by the controller); the body carries only the
 * mutable fields. {@code expectedVersion} is required for updates and ignored on insert.
 */
public record UpsertEquipmentRequest(
    boolean available, @Size(max = 255) String details, Long expectedVersion) {}
