package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.api.dto.HouseholdInviteDto;
import com.example.mealprep.household.api.dto.InviteStatus;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapperImpl;
import com.example.mealprep.household.domain.entity.HouseholdInvite;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.testdata.HouseholdTestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Targeted kills for {@link HouseholdInviteMapper}'s {@code deriveStatus} ladder (lines 47-56) and
 * the redacted-list helper (line 36-41). The baseline left the EXPIRED branch (line 53) SURVIVED
 * and all the NullReturnVals on each branch NO_COVERAGE — this test pins each return value.
 */
class HouseholdInviteMapperTest {

  private final HouseholdInviteMapper mapper = new HouseholdInviteMapperImpl();

  /**
   * kills HouseholdInviteMapper.java:48 NullReturnVals on the REVOKED return. A revoked invite
   * (even if also expired or accepted) must surface as REVOKED — the topmost branch.
   */
  @Test
  void deriveStatus_revokedInvite_returnsRevoked_killsLine48() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withRevokedAt(Instant.now().minus(1, ChronoUnit.DAYS))
            .withExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();

    HouseholdInviteDto dto = mapper.toDtoWithCode(invite);

    assertThat(dto.status()).isEqualTo(InviteStatus.REVOKED);
  }

  /**
   * Revoked dominates even when also accepted — order of checks matters. Pins the precedence at
   * line 47 (revoked check before accepted check).
   */
  @Test
  void deriveStatus_revokedAndAccepted_returnsRevoked() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withAcceptedAt(Instant.now().minus(2, ChronoUnit.DAYS))
            .withAcceptedByUserId(UUID.randomUUID())
            .withRevokedAt(Instant.now().minus(1, ChronoUnit.DAYS))
            .build();

    HouseholdInviteDto dto = mapper.toDtoWithCode(invite);

    assertThat(dto.status()).isEqualTo(InviteStatus.REVOKED);
  }

  /**
   * kills HouseholdInviteMapper.java:51 NullReturnVals on the ACCEPTED return. acceptedAt set, not
   * revoked, not expired -> ACCEPTED.
   */
  @Test
  void deriveStatus_acceptedNotRevoked_returnsAccepted_killsLine51() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withAcceptedAt(Instant.now().minus(2, ChronoUnit.HOURS))
            .withAcceptedByUserId(UUID.randomUUID())
            .withExpiresAt(Instant.now().plus(5, ChronoUnit.DAYS))
            .build();

    HouseholdInviteDto dto = mapper.toDtoWithCode(invite);

    assertThat(dto.status()).isEqualTo(InviteStatus.ACCEPTED);
  }

  /**
   * kills HouseholdInviteMapper.java:53 NegateConditionals on the {@code expiresAt.isBefore(now)}
   * comparison + :54 NullReturnVals on the EXPIRED return. A past expiresAt, not revoked, not
   * accepted -> EXPIRED.
   */
  @Test
  void deriveStatus_expiresAtInPast_returnsExpired_killsLines53And54() {
    HouseholdInvite invite =
        HouseholdTestData.invite().withExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();

    HouseholdInviteDto dto = mapper.toDtoWithCode(invite);

    assertThat(dto.status()).isEqualTo(InviteStatus.EXPIRED);
  }

  /**
   * Future expiresAt + no accept/revoke -> PENDING. Pins the line-56 PENDING fall-through against
   * the EXPIRED branch.
   */
  @Test
  void deriveStatus_futureExpiresAt_returnsPending() {
    HouseholdInvite invite =
        HouseholdTestData.invite().withExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS)).build();

    HouseholdInviteDto dto = mapper.toDtoWithCode(invite);

    assertThat(dto.status()).isEqualTo(InviteStatus.PENDING);
  }

  /**
   * kills HouseholdInviteMapper.java:44-45 — the {@code e == null} short-circuit on the helper
   * method.
   */
  @Test
  void deriveStatus_nullInvite_returnsNull() {
    // deriveStatus is invoked indirectly when mapping a null entity.
    HouseholdInviteDto dto = mapper.toDtoWithCode(null);
    assertThat(dto).isNull();
  }

  /** kills toDtoCodeRedacted — verifies the inviteCode is null in the redacted projection. */
  @Test
  void toDtoCodeRedacted_clearsInviteCode_andComputesStatus() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("VISIBLECODE12345")
            .withExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();

    HouseholdInviteDto dto = mapper.toDtoCodeRedacted(invite);

    assertThat(dto.inviteCode()).isNull();
    assertThat(dto.status()).isEqualTo(InviteStatus.PENDING);
  }

  /** kills toDtosCodeRedacted helper — list mapping preserves order + redacts every code. */
  @Test
  void toDtosCodeRedacted_mapsListInOrder_andRedactsAllCodes() {
    HouseholdInvite a =
        HouseholdTestData.invite()
            .withInviteCode("AAAAAAAAAAAAAAAA")
            .withIntendedRole(HouseholdRole.member)
            .withExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();
    HouseholdInvite b =
        HouseholdTestData.invite()
            .withInviteCode("BBBBBBBBBBBBBBBB")
            .withIntendedRole(HouseholdRole.primary)
            .withExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();

    List<HouseholdInviteDto> dtos = mapper.toDtosCodeRedacted(List.of(a, b));

    assertThat(dtos).hasSize(2);
    assertThat(dtos).allMatch(d -> d.inviteCode() == null);
    assertThat(dtos.get(0).intendedRole()).isEqualTo(HouseholdRole.member);
    assertThat(dtos.get(1).intendedRole()).isEqualTo(HouseholdRole.primary);
  }

  /** kills toDtosCodeRedacted helper null-input guard. */
  @Test
  void toDtosCodeRedacted_nullInput_returnsEmptyList() {
    assertThat(mapper.toDtosCodeRedacted(null)).isEmpty();
  }

  /** Sanity: toDtoWithCode preserves code when present (non-redacted path). */
  @Test
  void toDtoWithCode_preservesInviteCode() {
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withInviteCode("VISIBLECODE99999")
            .withExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .build();

    HouseholdInviteDto dto = mapper.toDtoWithCode(invite);

    assertThat(dto.inviteCode()).isEqualTo("VISIBLECODE99999");
  }
}
