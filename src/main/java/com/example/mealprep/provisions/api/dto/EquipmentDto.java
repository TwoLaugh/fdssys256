package com.example.mealprep.provisions.api.dto;

import java.util.UUID;

/**
 * Read shape of an equipment row. {@code version} is the JPA {@code @Version} value used for
 * optimistic-lock conflict detection on subsequent updates.
 */
public record EquipmentDto(
    UUID id, UUID userId, String name, boolean available, String details, long version) {}
