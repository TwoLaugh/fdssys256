package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.api.mapper.PreferenceArchiveMapper;
import com.example.mealprep.preference.domain.entity.PreferenceArchiveEntry;
import com.example.mealprep.preference.domain.repository.PreferenceArchiveRepository;
import com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.event.PreferenceArchivedEvent;
import com.example.mealprep.preference.event.PreferenceRePromotedEvent;
import com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link PreferenceArchiveQueryService} and {@link
 * PreferenceArchiveUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED. {@code archiveItem} validates its
 * request programmatically (the request never traverses a controller so the implicit Jakarta path
 * does not run for it) and appends a new row — there is no upsert, so re-archiving the same logical
 * item inserts another row. {@code markRePromoted} flips {@code re_promoted_at} on the currently
 * unpromoted entry and leaves it in place as history. Each successful write publishes exactly one
 * event AFTER the transaction commits.
 */
@Service
public class PreferenceArchiveServiceImpl
    implements PreferenceArchiveQueryService, PreferenceArchiveUpdateService {

  private static final Logger log = LoggerFactory.getLogger(PreferenceArchiveServiceImpl.class);

  private final PreferenceArchiveRepository archiveRepository;
  private final PreferenceArchiveMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final Validator validator;
  private final Clock clock;

  public PreferenceArchiveServiceImpl(
      PreferenceArchiveRepository archiveRepository,
      PreferenceArchiveMapper mapper,
      ApplicationEventPublisher eventPublisher,
      Validator validator,
      Clock clock) {
    this.archiveRepository = archiveRepository;
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.validator = validator;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Page<PreferenceArchiveEntryDto> getArchive(UUID userId, Pageable pageable) {
    return archiveRepository.findByUserIdOrderByArchivedAtDesc(userId, pageable).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PreferenceArchiveEntryDto> getArchiveForField(
      UUID userId, String fieldPathPrefix, Pageable pageable) {
    return archiveRepository
        .findByUserIdAndFieldPathStartingWithOrderByArchivedAtDesc(
            userId, fieldPathPrefix, pageable)
        .map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PreferenceArchiveEntryDto> getFullArchive(UUID userId) {
    return mapper.toDtos(archiveRepository.findAllByUserId(userId));
  }

  @Override
  @Transactional(readOnly = true)
  public long countActiveEntries(UUID userId) {
    return archiveRepository.countByUserIdAndRePromotedAtIsNull(userId);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public PreferenceArchiveEntryDto archiveItem(UUID userId, ArchiveItemRequest request) {
    Set<ConstraintViolation<ArchiveItemRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }

    Instant now = Instant.now(clock);
    PreferenceArchiveEntry entry =
        PreferenceArchiveEntry.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .fieldPath(request.fieldPath())
            .itemKey(request.itemKey())
            .itemPayload(request.itemPayload())
            .evidenceCount(request.evidenceCount())
            .lastSignalAt(request.lastSignalAt())
            .archivedAt(now)
            .archivedReason(request.reason())
            .rePromotedAt(null)
            .build();
    PreferenceArchiveEntry saved = archiveRepository.save(entry);

    eventPublisher.publishEvent(
        new PreferenceArchivedEvent(
            userId,
            saved.getId(),
            saved.getFieldPath(),
            saved.getItemKey(),
            saved.getArchivedReason(),
            UUID.randomUUID(),
            now));

    log.info(
        "archived taste-profile item userId={} archiveEntryId={} fieldPath={} itemKey={} reason={}",
        userId,
        saved.getId(),
        saved.getFieldPath(),
        saved.getItemKey(),
        saved.getArchivedReason());
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public PreferenceArchiveEntryDto markRePromoted(UUID userId, String fieldPath, String itemKey) {
    PreferenceArchiveEntry entry =
        archiveRepository
            .findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull(userId, fieldPath, itemKey)
            .orElseThrow(
                () -> new PreferenceArchiveEntryNotFoundException(userId, fieldPath, itemKey));

    Instant now = Instant.now(clock);
    entry.setRePromotedAt(now);
    PreferenceArchiveEntry saved = archiveRepository.save(entry);

    eventPublisher.publishEvent(
        new PreferenceRePromotedEvent(
            userId,
            saved.getId(),
            saved.getFieldPath(),
            saved.getItemKey(),
            UUID.randomUUID(),
            now));

    log.info(
        "re-promoted archived taste-profile item userId={} archiveEntryId={} fieldPath={} itemKey={}",
        userId,
        saved.getId(),
        saved.getFieldPath(),
        saved.getItemKey());
    return mapper.toDto(saved);
  }
}
