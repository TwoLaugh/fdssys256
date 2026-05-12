package com.example.mealprep.recipe.domain.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps the Java {@code float[]} embedding to/from the pgvector text wire format {@code
 * '[v1,v2,...,vN]'} that PostgreSQL's pgvector extension accepts via its implicit {@code text ->
 * vector} cast. The Hibernate JDBC binding for {@code String} writes the literal as-is; pgvector
 * parses it on the way in and emits it the same way on the way out.
 *
 * <p>Locale-independent: {@link Float#toString(float)} always uses {@code '.'} as the decimal
 * separator regardless of {@link java.util.Locale#getDefault()}, so two JVMs in different locales
 * produce byte-identical strings for the same vector. The round-trip preserves the float value
 * exactly (within FP precision — {@link Float#parseFloat(String)} on the output of {@link
 * Float#toString(float)} yields the same {@code float}).
 *
 * <p>{@code null} attribute → {@code null} column (the common case — newly-created versions start
 * with {@code embedding_status = 'pending'} and a NULL vector). Empty array → {@code "[]"} → empty
 * array; pgvector does not allow zero-dimension vector values on a {@code vector(1536)} column, so
 * this branch is purely defensive (it lets the converter be unit-tested without a DB round-trip).
 */
@Converter
public class RecipeEmbeddingConverter implements AttributeConverter<float[], String> {

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
