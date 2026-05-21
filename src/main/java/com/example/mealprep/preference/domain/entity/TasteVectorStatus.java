package com.example.mealprep.preference.domain.entity;

/**
 * Lifecycle of the {@code taste_vector} pgvector embedding. {@code PENDING} = needs (re-)compute;
 * {@code EMBEDDED} = up-to-date for the recorded {@code tasteVectorDocVersion}; {@code FAILED} =
 * the async listener gave up.
 *
 * <p>The vector column itself is deferred to a follow-up ticket; only the scalar status fields ship
 * in 01c so the future ticket adds the vector column + HNSW index without back-touching the row
 * shape.
 */
public enum TasteVectorStatus {
  PENDING,
  EMBEDDED,
  FAILED
}
