package com.example.mealprep.core.origin;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Request-scoped carrier of origin metadata, populated once by {@link OriginFilter} at the front of
 * the chain and read by downstream services when they write audit rows / publish events.
 *
 * <p>Default state — what a fresh request looks like before the filter runs and what a plain user
 * request looks like throughout — is {@code (origin=USER, originTrace=null, originDepth=0,
 * confidence=null, actingAsUserId=null)}.
 *
 * <p>Setters are package-private: only {@link OriginFilter} is expected to populate this bean.
 * Services consume the read-only view via getters.
 */
@Component
@RequestScope
public class OriginContext {

  private Origin origin = Origin.USER;
  private String originTrace;
  private int originDepth;
  private BigDecimal confidence;
  private UUID actingAsUserId;

  public Origin getOrigin() {
    return origin;
  }

  public String getOriginTrace() {
    return originTrace;
  }

  public int getOriginDepth() {
    return originDepth;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public UUID getActingAsUserId() {
    return actingAsUserId;
  }

  /** True for any request whose origin is anything other than {@link Origin#USER}. */
  public boolean isNonUserOrigin() {
    return origin != Origin.USER;
  }

  // Package-private setters — only OriginFilter populates this bean.

  void setOrigin(Origin origin) {
    this.origin = origin;
  }

  void setOriginTrace(String originTrace) {
    this.originTrace = originTrace;
  }

  void setOriginDepth(int originDepth) {
    this.originDepth = originDepth;
  }

  void setConfidence(BigDecimal confidence) {
    this.confidence = confidence;
  }

  void setActingAsUserId(UUID actingAsUserId) {
    this.actingAsUserId = actingAsUserId;
  }
}
