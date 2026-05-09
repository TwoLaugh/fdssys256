package com.example.mealprep.nutrition.domain.entity;

/**
 * Origin of an audit-log row. 01a only writes {@link #USER}; the other values are reserved so
 * downstream sub-tickets ({@code HEALTH_DIRECTIVE} in 01e, {@code FEEDBACK} in later tickets) layer
 * additional actor sources without a migration.
 */
public enum ActorKind {
  USER,
  HEALTH_DIRECTIVE,
  FEEDBACK
}
