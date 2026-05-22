package com.example.mealprep.core.origin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pinning for {@link AuditMetadata} factories. The two factories are tiny but load-bearing — every
 * future audit-log write that wants origin attribution will go through them, so a {@code null}
 * mutation on either {@code actorType()} accessor would silently break audit attribution.
 */
class AuditMetadataTest {

  @Test
  void fromContext_USER_origin_yields_USER_actorType_and_null_trace() {
    OriginContext ctx = new OriginContext();
    // Default state is USER / null / 0.

    AuditMetadata md = AuditMetadata.fromContext(ctx);

    assertThat(md.actorType()).isEqualTo(ActorType.USER);
    assertThat(md.originTrace()).isNull();
  }

  @Test
  void fromContext_AI_FEEDBACK_origin_yields_AI_actorType_and_preserved_trace() {
    OriginContext ctx = new OriginContext();
    ctx.setOrigin(Origin.AI_FEEDBACK);
    ctx.setOriginTrace("feedback-abc");

    AuditMetadata md = AuditMetadata.fromContext(ctx);

    assertThat(md.actorType()).isEqualTo(ActorType.AI);
    assertThat(md.originTrace()).isEqualTo("feedback-abc");
  }

  @Test
  void fromContext_SYSTEM_SCHEDULED_origin_yields_SYSTEM_actorType() {
    OriginContext ctx = new OriginContext();
    ctx.setOrigin(Origin.SYSTEM_SCHEDULED);
    ctx.setOriginTrace("scheduled-price-refresh-2026-05-21");

    AuditMetadata md = AuditMetadata.fromContext(ctx);

    assertThat(md.actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(md.originTrace()).isEqualTo("scheduled-price-refresh-2026-05-21");
  }

  @Test
  void user_factory_returns_USER_with_null_trace() {
    AuditMetadata md = AuditMetadata.user();

    assertThat(md.actorType()).isEqualTo(ActorType.USER);
    assertThat(md.originTrace()).isNull();
  }

  @Test
  void origin_toActorType_covers_every_origin_value() {
    // Defence-in-depth: if a new Origin lands without an actorType mapping, this loop trips.
    for (Origin origin : Origin.values()) {
      ActorType actor = origin.toActorType();
      assertThat(actor).as("origin %s must map to a non-null ActorType", origin).isNotNull();
    }
  }

  @Test
  void ai_origins_all_map_to_AI_actorType() {
    assertThat(Origin.AI_FEEDBACK.toActorType()).isEqualTo(ActorType.AI);
    assertThat(Origin.AI_ADAPTATION.toActorType()).isEqualTo(ActorType.AI);
  }

  @Test
  void system_origins_all_map_to_SYSTEM_actorType() {
    assertThat(Origin.SYSTEM_SCHEDULED.toActorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(Origin.SYSTEM_REOPT.toActorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(Origin.SYSTEM_DISCOVERY.toActorType()).isEqualTo(ActorType.SYSTEM);
  }
}
