package com.example.mealprep.provisions.exception;

import java.util.UUID;

/**
 * Thrown when an equipment row is missing for the calling user. Mapped to HTTP 404 — equipment is
 * always per-user, so a missing row for the caller's userId is indistinguishable (and treated
 * identically) from an existing row owned by another user.
 */
public class EquipmentNotFoundException extends ProvisionsException {

  private final UUID userId;
  private final String name;

  public EquipmentNotFoundException(UUID userId, String name) {
    super("Equipment '" + name + "' not found for user " + userId);
    this.userId = userId;
    this.name = name;
  }

  public UUID userId() {
    return userId;
  }

  public String equipmentName() {
    return name;
  }
}
