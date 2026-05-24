package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.entity.TasteProfileVersion;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.TasteProfileVersionNotFoundException;
import com.example.mealprep.preference.spi.internal.PreferenceFeedbackReverterImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link PreferenceFeedbackReverterImpl} — clean rollback / partial (newer deltas) /
 * unresolvable version / never-throw (feedback-01h edge-case checklist).
 */
class PreferenceFeedbackReverterTest {

  private final TasteProfileQueryService queryService = mock(TasteProfileQueryService.class);
  private final TasteProfileUpdateService updateService = mock(TasteProfileUpdateService.class);
  private final TasteProfileVersionRepository versionRepository =
      mock(TasteProfileVersionRepository.class);
  private final PreferenceFeedbackReverterImpl reverter =
      new PreferenceFeedbackReverterImpl(queryService, updateService, versionRepository);

  @Test
  void revert_cleanRollback_rollsBackToPredecessorVersion() {
    UUID userId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    String trace = "feedback-" + UUID.randomUUID();

    when(queryService.getTasteProfile(userId))
        .thenReturn(Optional.of(profile(profileId, userId, 12L)));
    when(versionRepository.findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
            profileId, trace))
        .thenReturn(Optional.of(version(15)));
    when(updateService.rollbackTasteProfile(eq(userId), eq(14), eq(12L), eq(userId)))
        .thenReturn(profile(profileId, userId, 13L));

    reverter.revert(ctx(userId, trace));

    // Rolls back to the version that preceded the applied delta batch (15 - 1), echoing the
    // current optimistic version as expectedVersion and attributing the actor to the same user.
    verify(updateService).rollbackTasteProfile(userId, 14, 12L, userId);
  }

  @Test
  void revert_newerDeltasAppliedOnTop_bestEffortSwallowsOptimisticLockConflict() {
    UUID userId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    String trace = "feedback-" + UUID.randomUUID();

    when(queryService.getTasteProfile(userId))
        .thenReturn(Optional.of(profile(profileId, userId, 20L)));
    when(versionRepository.findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
            profileId, trace))
        .thenReturn(Optional.of(version(15)));
    when(updateService.rollbackTasteProfile(eq(userId), eq(14), eq(20L), eq(userId)))
        .thenThrow(new ObjectOptimisticLockingFailureException("taste_profile", userId));

    // Newer deltas advanced the version: a clean revert is impossible; replay-from-cursor
    // reconciles. The reverter logs the divergence and does NOT throw.
    assertThatCode(() -> reverter.revert(ctx(userId, trace))).doesNotThrowAnyException();
  }

  @Test
  void revert_noOriginTrace_isLogOnlyAndDoesNotRollBack() {
    UUID userId = UUID.randomUUID();
    RevertContext ctx =
        new RevertContext(
            UUID.randomUUID(),
            userId,
            UUID.randomUUID(),
            Destination.PREFERENCE,
            null,
            JsonNodeFactory.instance.objectNode());

    reverter.revert(ctx);

    verifyNoInteractions(queryService, updateService, versionRepository);
  }

  @Test
  void revert_noProfile_isLogOnlyAndDoesNotRollBack() {
    UUID userId = UUID.randomUUID();
    when(queryService.getTasteProfile(userId)).thenReturn(Optional.empty());

    reverter.revert(ctx(userId, "feedback-" + UUID.randomUUID()));

    verify(updateService, never()).rollbackTasteProfile(any(), anyInt(), anyLong(), any());
  }

  @Test
  void revert_noVersionSnapshotForTrace_isLogOnlyAndDoesNotRollBack() {
    UUID userId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    String trace = "feedback-" + UUID.randomUUID();
    when(queryService.getTasteProfile(userId))
        .thenReturn(Optional.of(profile(profileId, userId, 5L)));
    when(versionRepository.findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
            profileId, trace))
        .thenReturn(Optional.empty());

    reverter.revert(ctx(userId, trace));

    verify(updateService, never()).rollbackTasteProfile(any(), anyInt(), anyLong(), any());
  }

  @Test
  void revert_appliedAtFirstVersion_hasNoPredecessor_isLogOnly() {
    UUID userId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    String trace = "feedback-" + UUID.randomUUID();
    when(queryService.getTasteProfile(userId))
        .thenReturn(Optional.of(profile(profileId, userId, 1L)));
    when(versionRepository.findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
            profileId, trace))
        .thenReturn(Optional.of(version(1)));

    reverter.revert(ctx(userId, trace));

    verify(updateService, never()).rollbackTasteProfile(any(), anyInt(), anyLong(), any());
  }

  @Test
  void revert_rollbackThrowsDomainException_swallowsAndDoesNotThrow() {
    UUID userId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    String trace = "feedback-" + UUID.randomUUID();
    when(queryService.getTasteProfile(userId))
        .thenReturn(Optional.of(profile(profileId, userId, 9L)));
    when(versionRepository.findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
            profileId, trace))
        .thenReturn(Optional.of(version(4)));
    when(updateService.rollbackTasteProfile(eq(userId), eq(3), eq(9L), eq(userId)))
        .thenThrow(new TasteProfileVersionNotFoundException(userId, 3));

    assertThatCode(() -> reverter.revert(ctx(userId, trace))).doesNotThrowAnyException();
  }

  private static RevertContext ctx(UUID userId, String originTrace) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("status", "DISPATCHED");
    result.put("originTrace", originTrace);
    return new RevertContext(
        UUID.randomUUID(), userId, UUID.randomUUID(), Destination.PREFERENCE, null, result);
  }

  private static TasteProfileDto profile(UUID id, UUID userId, long optimisticVersion) {
    return new TasteProfileDto(
        id,
        userId,
        null,
        1,
        null,
        0,
        null,
        null,
        TasteVectorStatus.EMBEDDED,
        optimisticVersion,
        Instant.now(),
        Instant.now());
  }

  private static TasteProfileVersion version(int documentVersion) {
    return TasteProfileVersion.builder().documentVersion(documentVersion).build();
  }
}
