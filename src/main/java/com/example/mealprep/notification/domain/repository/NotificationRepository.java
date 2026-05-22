package com.example.mealprep.notification.domain.repository;

import com.example.mealprep.notification.domain.entity.Notification;
import com.example.mealprep.notification.domain.entity.NotificationKind;
import com.example.mealprep.notification.domain.entity.NotificationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Notification}. Module-private at the package level —
 * cross-module access goes through {@code NotificationQueryService} / {@code
 * NotificationUpdateService} only, enforced by {@code NotificationBoundaryTest}.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

  /**
   * Inbox search with optional status / kind / since filters. A null (or empty) collection means
   * "no constraint on that dimension"; a null {@code since} means no lower time bound.
   * Newest-first.
   */
  @Query(
      """
      select n from Notification n
      where n.userId = :userId
        and (:statuses is null or n.status in :statuses)
        and (:kinds is null or n.kind in :kinds)
        and (:since is null or n.createdAt >= :since)
      order by n.createdAt desc
      """)
  Page<Notification> search(
      @Param("userId") UUID userId,
      @Param("statuses") Collection<NotificationStatus> statuses,
      @Param("kinds") Collection<NotificationKind> kinds,
      @Param("since") Instant since,
      Pageable pageable);

  Page<Notification> findByUserIdAndStatusInOrderByCreatedAtDesc(
      UUID userId, Collection<NotificationStatus> statuses, Pageable pageable);

  Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  long countByUserIdAndStatus(UUID userId, NotificationStatus status);

  long countByUserIdAndStatusAndSeverity(
      UUID userId,
      NotificationStatus status,
      com.example.mealprep.notification.domain.entity.NotificationSeverity severity);

  List<Notification> findByUserIdAndStatus(UUID userId, NotificationStatus status);

  List<Notification> findByUserIdAndStatusAndKindIn(
      UUID userId, NotificationStatus status, Collection<NotificationKind> kinds);

  /**
   * Most-recent open ({@code UNREAD}) notifications of a given {@code (user, kind)} created on or
   * after {@code since} — the debouncer's single-flight bundle-target lookup. Caller passes {@code
   * PageRequest.of(0, 1)} to LIMIT 1.
   */
  @Query(
      """
      select n from Notification n
      where n.userId = :userId
        and n.kind = :kind
        and n.status = com.example.mealprep.notification.domain.entity.NotificationStatus.UNREAD
        and n.createdAt >= :since
      order by n.createdAt desc
      """)
  List<Notification> findOpenForBundling(
      @Param("userId") UUID userId,
      @Param("kind") NotificationKind kind,
      @Param("since") Instant since,
      Pageable pageable);

  /** Bulk-mark all of a user's {@code UNREAD} notifications {@code READ}. */
  @Modifying
  @Query(
      """
      update Notification n
      set n.status = com.example.mealprep.notification.domain.entity.NotificationStatus.READ,
          n.readAt = :now
      where n.userId = :userId
        and n.status = com.example.mealprep.notification.domain.entity.NotificationStatus.UNREAD
      """)
  int markAllReadForUser(@Param("userId") UUID userId, @Param("now") Instant now);

  /** Bulk-mark a user's {@code UNREAD} notifications of the given kinds {@code READ}. */
  @Modifying
  @Query(
      """
      update Notification n
      set n.status = com.example.mealprep.notification.domain.entity.NotificationStatus.READ,
          n.readAt = :now
      where n.userId = :userId
        and n.status = com.example.mealprep.notification.domain.entity.NotificationStatus.UNREAD
        and n.kind in :kinds
      """)
  int markReadForUserAndKinds(
      @Param("userId") UUID userId,
      @Param("kinds") Collection<NotificationKind> kinds,
      @Param("now") Instant now);
}
