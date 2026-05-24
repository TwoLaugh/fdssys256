package com.example.mealprep.ai.domain.entity;

import com.example.mealprep.ai.spi.ModelTier;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

/**
 * Append-only prompt template row. The {@code PromptTemplateLoader} INSERTs a new {@code (name,
 * version)} every time a source file's {@code sha256} differs from the latest known version; rows
 * are never updated.
 */
@Entity
@Table(name = "ai_prompt_template")
public class PromptTemplate {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "name", updatable = false, nullable = false, length = 128)
  private String name;

  @Column(name = "version", updatable = false, nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(name = "model_tier", updatable = false, nullable = false, length = 16)
  private ModelTier modelTier;

  @Column(name = "system_prompt", updatable = false, nullable = false, columnDefinition = "text")
  private String systemPrompt;

  @Column(
      name = "user_prompt_template",
      updatable = false,
      nullable = false,
      columnDefinition = "text")
  private String userPromptTemplate;

  @Type(JsonBinaryType.class)
  @Column(name = "output_schema", updatable = false, columnDefinition = "jsonb")
  private JsonNode outputSchema;

  @Type(JsonBinaryType.class)
  @Column(name = "tools", updatable = false, columnDefinition = "jsonb")
  private JsonNode tools;

  @Column(name = "notes", updatable = false, columnDefinition = "text")
  private String notes;

  @Column(name = "source_file", updatable = false, nullable = false, length = 255)
  private String sourceFile;

  // sha256 hex is always exactly 64 chars; the migration deliberately uses fixed-length char(64)
  // (Postgres bpchar). A plain String column maps to JDBC VARCHAR, which Hibernate's
  // ddl-auto=validate (prod/dev/e2e) rejects against the bpchar/Types#CHAR the DB reports. Pinning
  // the JDBC type to CHAR keeps validate in agreement with the deployed column (caught by
  // SchemaValidationIT). The value is exactly length chars, so there is no CHAR space-padding.
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "source_hash", updatable = false, nullable = false, length = 64)
  private String sourceHash;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  /** For Hibernate. Not for application code. */
  protected PromptTemplate() {}

  public PromptTemplate(
      UUID id,
      String name,
      int version,
      ModelTier modelTier,
      String systemPrompt,
      String userPromptTemplate,
      JsonNode outputSchema,
      JsonNode tools,
      String notes,
      String sourceFile,
      String sourceHash) {
    this.id = id;
    this.name = name;
    this.version = version;
    this.modelTier = modelTier;
    this.systemPrompt = systemPrompt;
    this.userPromptTemplate = userPromptTemplate;
    this.outputSchema = outputSchema;
    this.tools = tools;
    this.notes = notes;
    this.sourceFile = sourceFile;
    this.sourceHash = sourceHash;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public int getVersion() {
    return version;
  }

  public ModelTier getModelTier() {
    return modelTier;
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public String getUserPromptTemplate() {
    return userPromptTemplate;
  }

  public JsonNode getOutputSchema() {
    return outputSchema;
  }

  public JsonNode getTools() {
    return tools;
  }

  public String getNotes() {
    return notes;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
