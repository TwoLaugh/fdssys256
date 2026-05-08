package com.example.mealprep.ai.domain.entity;

import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only-with-one-update audit row for a single {@code AiService.execute} call. The dispatcher
 * inserts in {@link CallStatus#PENDING} and exactly once updates to {@link CallStatus#SUCCEEDED} or
 * {@link CallStatus#FAILED}; no {@code @Version} because only one race participant ever issues that
 * update (the call is single-threaded inside the dispatcher).
 *
 * <p>Cost calculation lands in 01b — for 01a {@link #costMicroPence} is always {@code 0}.
 */
@Entity
@Table(name = "ai_call_log")
public class AiCallLog {

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "user_id", updatable = false)
  private UUID userId;

  @Column(name = "trace_id", updatable = false)
  private UUID traceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", updatable = false, nullable = false, length = 64)
  private TaskType taskType;

  @Enumerated(EnumType.STRING)
  @Column(name = "model_tier", updatable = false, nullable = false, length = 16)
  private ModelTier modelTier;

  @Column(name = "model_id", updatable = false, nullable = false, length = 96)
  private String modelId;

  @Column(name = "prompt_ref_name", updatable = false, length = 128)
  private String promptRefName;

  @Column(name = "prompt_ref_version", updatable = false)
  private Integer promptRefVersion;

  @Column(name = "request_tokens")
  private Integer requestTokens;

  @Column(name = "response_tokens")
  private Integer responseTokens;

  // 01b will compute cost from request/response tokens + tier pricing.
  @Column(name = "cost_micro_pence", nullable = false)
  private long costMicroPence;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private CallStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_kind", length = 32)
  private CallErrorKind errorKind;

  @Column(name = "latency_ms")
  private Integer latencyMs;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  /** For Hibernate. Not for application code. */
  protected AiCallLog() {}

  public AiCallLog(
      UUID id,
      UUID userId,
      UUID traceId,
      TaskType taskType,
      ModelTier modelTier,
      String modelId,
      String promptRefName,
      Integer promptRefVersion,
      CallStatus status) {
    this.id = id;
    this.userId = userId;
    this.traceId = traceId;
    this.taskType = taskType;
    this.modelTier = modelTier;
    this.modelId = modelId;
    this.promptRefName = promptRefName;
    this.promptRefVersion = promptRefVersion;
    this.status = status;
    this.costMicroPence = 0L;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getTraceId() {
    return traceId;
  }

  public TaskType getTaskType() {
    return taskType;
  }

  public ModelTier getModelTier() {
    return modelTier;
  }

  public String getModelId() {
    return modelId;
  }

  public String getPromptRefName() {
    return promptRefName;
  }

  public Integer getPromptRefVersion() {
    return promptRefVersion;
  }

  public Integer getRequestTokens() {
    return requestTokens;
  }

  public void setRequestTokens(Integer requestTokens) {
    this.requestTokens = requestTokens;
  }

  public Integer getResponseTokens() {
    return responseTokens;
  }

  public void setResponseTokens(Integer responseTokens) {
    this.responseTokens = responseTokens;
  }

  public long getCostMicroPence() {
    return costMicroPence;
  }

  public void setCostMicroPence(long costMicroPence) {
    this.costMicroPence = costMicroPence;
  }

  public CallStatus getStatus() {
    return status;
  }

  public void setStatus(CallStatus status) {
    this.status = status;
  }

  public CallErrorKind getErrorKind() {
    return errorKind;
  }

  public void setErrorKind(CallErrorKind errorKind) {
    this.errorKind = errorKind;
  }

  public Integer getLatencyMs() {
    return latencyMs;
  }

  public void setLatencyMs(Integer latencyMs) {
    this.latencyMs = latencyMs;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }
}
