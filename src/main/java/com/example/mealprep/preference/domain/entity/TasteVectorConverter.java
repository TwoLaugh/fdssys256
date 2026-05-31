package com.example.mealprep.preference.domain.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps the Java {@code float[]} taste embedding to/from the pgvector text wire format {@code
 * '[v1,v2,...,vN]'} that PostgreSQL's pgvector extension accepts via its implicit {@code text ->
 * vector} cast. Mirrors {@code recipe.domain.entity.RecipeEmbeddingConverter} — the two modules
 * persist embeddings the same way, but each keeps its own converter so neither reaches across the
 * module boundary.
 *
 * <p>The Hibernate JDBC binding for {@code String} writes the literal as-is; a
 * {@code @ColumnTransformer(write = "?::vector")} on the field wraps the bound parameter in an
 * explicit server-side cast so the {@code varchar -> vector} conversion happens in Postgres.
 *
 * <p>Locale-independent: {@link Float#toString(float)} always uses {@code '.'} as the decimal
 * separator regardless of {@link java.util.Locale#getDefault()}, so two JVMs in different locales
 * produce byte-identical strings for the same vector. {@link Float#parseFloat(String)} on the
 * output of {@link Float#toString(float)} yields the same {@code float}, so the round-trip is exact
 * within FP precision.
 *
 * <p>{@code null} attribute → {@code null} column (the common case — a freshly-initialised profile
 * has {@code taste_vector_status = PENDING} and a NULL vector). The empty-array branch is purely
 * defensive (pgvector rejects zero-dimension values on a {@code vector(1536)} column); it lets the
 * converter be unit-tested without a DB round-trip.
 */
@Converter
public class TasteVectorConverter implements AttributeConverter<float[], String> {

  @Override
  public String convertToDatabaseColumn(float[] attribute) {
    if (attribute == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(attribute.length * 8 + 2);
    sb.append('[');
    for (int i = 0; i < attribute.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(attribute[i]));
    }
    sb.append(']');
    return sb.toString();
  }

  @Override
  public float[] convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    String trimmed = dbData.trim();
    if (trimmed.length() < 2
        || trimmed.charAt(0) != '['
        || trimmed.charAt(trimmed.length() - 1) != ']') {
      throw new IllegalStateException("pgvector text value missing '[]' wrapper: " + dbData);
    }
    String stripped = trimmed.substring(1, trimmed.length() - 1);
    if (stripped.isEmpty()) {
      return new float[0];
    }
    String[] parts = stripped.split(",");
    float[] out = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      out[i] = Float.parseFloat(parts[i]);
    }
    return out;
  }
}
