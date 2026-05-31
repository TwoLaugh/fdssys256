package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.preference.domain.entity.TasteVectorConverter;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TasteVectorConverter} (preference-5). Mirrors {@code
 * RecipeEmbeddingConverterTest}: null/empty edges, the round-trip shape (exact within FP precision
 * across 1536 dims), and the locale-independence requirement so a comma-decimal JVM (e.g. de-DE)
 * does not emit a pgvector-incompatible literal.
 */
class TasteVectorConverterTest {

  private final TasteVectorConverter converter = new TasteVectorConverter();

  @Test
  void writeNull_returnsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  void writeEmpty_returnsBracketPair() {
    assertThat(converter.convertToDatabaseColumn(new float[0])).isEqualTo("[]");
  }

  @Test
  void writeSmallVector_returnsBracketedCsv() {
    assertThat(converter.convertToDatabaseColumn(new float[] {0.1f, 0.2f, 0.3f}))
        .isEqualTo("[0.1,0.2,0.3]");
  }

  @Test
  void readNull_returnsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void readEmpty_returnsEmptyArray() {
    assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
  }

  @Test
  void readSmallVector_returnsFloats() {
    assertThat(converter.convertToEntityAttribute("[0.1,0.2,0.3]"))
        .containsExactly(0.1f, 0.2f, 0.3f);
  }

  @Test
  void readMalformed_throws() {
    assertThatThrownBy(() -> converter.convertToEntityAttribute("0.1,0.2,0.3"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void roundTrip_1536Dim_isExact() {
    float[] vector = new float[1536];
    long state = 11L;
    for (int i = 0; i < vector.length; i++) {
      state ^= state << 13;
      state ^= state >>> 7;
      state ^= state << 17;
      vector[i] = ((state & 0xFFFF) / 65535.0f) * 2f - 1f;
    }
    String wire = converter.convertToDatabaseColumn(vector);
    assertThat(converter.convertToEntityAttribute(wire)).containsExactly(vector);
  }

  @Test
  void localeIndependent_decimalSeparatorAlwaysDot() {
    Locale prior = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMAN);
      String wire = converter.convertToDatabaseColumn(new float[] {0.5f, 1.25f});
      assertThat(wire).isEqualTo("[0.5,1.25]");
      assertThat(wire).doesNotContain("0,5").doesNotContain("1,25");
    } finally {
      Locale.setDefault(prior);
    }
  }
}
