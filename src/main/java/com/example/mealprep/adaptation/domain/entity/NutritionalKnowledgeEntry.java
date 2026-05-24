package com.example.mealprep.adaptation.domain.entity;

import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

/**
 * Read-only reference-data row carrying a single food-science fact (pairing, bioavailability, soak
 * requirement, absorption conflict). {@code subjectKeys} is a Postgres {@code text[]} mapped via
 * Hibernate 6's native array support ({@code @JdbcTypeCode(SqlTypes.ARRAY)}); the GIN index on the
 * column enables the {@code subject_keys && cast(:keys as text[])} intersect that {@code
 * NutritionalKnowledgeRepository} relies on.
 *
 * <p>Native {@code SqlTypes.ARRAY} mapping (not hypersistence-utils' {@code StringArrayType}) is
 * deliberate: the hypersistence array type reports JDBC type {@code OTHER}, which Hibernate's
 * schema validator rejects against Postgres' reported {@code _text (Types#ARRAY)} under {@code
 * ddl-auto=validate} (prod/dev/e2e). Native ARRAY mapping reports {@code Types#ARRAY}, so {@code
 * validate} passes while array read/write is unchanged. See {@code SchemaValidationIT}.
 *
 * <p>No {@code @Version} — this is reference data; rows are inserted once via the {@code
 * R__adaptation_seed_nutritional_knowledge_v1.sql} repeatable migration. See {@code
 * lld/adaptation-pipeline.md} §V20260615120500 (lines 250-264).
 */
@Entity
@Table(name = "adaptation_nutritional_knowledge")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class NutritionalKnowledgeEntry {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "knowledge_kind", nullable = false, length = 32)
  private KnowledgeKind knowledgeKind;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "subject_keys", nullable = false, columnDefinition = "text[]")
  private String[] subjectKeys;

  @Type(JsonBinaryType.class)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private JsonNode payload;

  @Column(name = "confidence_tier", nullable = false, length = 16)
  private String confidenceTier;

  @Column(name = "source", nullable = false, length = 64)
  private String source;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
