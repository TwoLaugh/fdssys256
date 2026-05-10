package com.example.mealprep.recipe.domain.entity;

/**
 * The provenance class of a {@code RecipeImport} row. Stored as the lower-cased enum name in the
 * {@code source_type} varchar(16) column.
 *
 * <p>01b only ever writes {@link #URL}; the remaining values are reserved for the AI generator,
 * web-discovery crawler, and explicit manual-create rows that may be backfilled in later sub-
 * tickets.
 */
public enum ImportSource {
  MANUAL,
  URL,
  AI_GENERATED,
  WEB_DISCOVERED
}
