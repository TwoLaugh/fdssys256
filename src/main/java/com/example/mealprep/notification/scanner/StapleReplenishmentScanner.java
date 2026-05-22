package com.example.mealprep.notification.scanner;

import com.example.mealprep.notification.domain.repository.StapleReplenishmentDispatchLogRepository;
import com.example.mealprep.notification.event.StapleReplenishmentNeededEvent;
import com.example.mealprep.notification.scanner.config.ScannerProperties;
import com.example.mealprep.notification.scanner.internal.ScannerSupport;
import com.example.mealprep.notification.scanner.internal.entity.StapleReplenishmentDispatchLog;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly scanner (Sundays at 10:00 by default) that flags staple inventory at/below restock level.
 * Per {@code tickets/notification/01b-scanners.md} §Scanner #5.
 *
 * <p>For each user with active inventory it loads the staple items whose status is {@code LOW} or
 * {@code OUT} (the provisions {@code StapleStatus} stands in for an explicit restock-threshold,
 * which the inventory model does not carry numerically) and fires <strong>one</strong> {@code
 * StapleReplenishmentNeededEvent} per user-batch. The event's {@code lowestStockRatio} is 0 when
 * any item is fully {@code OUT}, else {@code 0.5} for {@code LOW}-only batches.
 *
 * <p>Idempotent per {@code (userId, scanDate)} via {@link
 * StapleReplenishmentDispatchLogRepository}.
 */
@Component
@ConditionalOnProperty(
    prefix = "mealprep.notification.scanners",
    name = "staple-replenishment.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StapleReplenishmentScanner extends ScannerSupport {

  private static final BigDecimal RATIO_OUT = BigDecimal.ZERO;
  private static final BigDecimal RATIO_LOW = new BigDecimal("0.5");

  private final ProvisionQueryService provisionQueryService;
  private final StapleReplenishmentDispatchLogRepository dispatchLogRepository;

  public StapleReplenishmentScanner(
      Clock clock,
      ApplicationEventPublisher eventPublisher,
      ProvisionQueryService provisionQueryService,
      StapleReplenishmentDispatchLogRepository dispatchLogRepository,
      ScannerProperties properties) {
    super(clock, eventPublisher);
    this.provisionQueryService = provisionQueryService;
    this.dispatchLogRepository = dispatchLogRepository;
  }

  /** Scheduled trigger — Sundays 10:00 by default. The cron is far-future in the test profile. */
  @Scheduled(cron = "${mealprep.notification.scanners.staple-replenishment.cron:0 0 10 * * SUN}")
  @Transactional
  public int runScheduled() {
    return scan();
  }

  /** Single synchronous scan; returns the number of users for whom a replenishment alert fired. */
  @Transactional
  public int scan() {
    Instant now = now();
    ZoneId zone = clock().getZone();
    LocalDate today = LocalDate.ofInstant(now, zone);
    int fired = 0;
    for (UUID userId : provisionQueryService.getUserIdsWithActiveInventory()) {
      try {
        if (dispatchLogRepository.existsByUserIdAndScanDate(userId, today)) {
          continue;
        }
        List<InventoryItemDto> staples =
            provisionQueryService.getStaplesNeedingReplenishment(userId);
        if (staples.isEmpty()) {
          continue;
        }
        List<UUID> itemIds = new ArrayList<>();
        List<String> mappingKeys = new ArrayList<>();
        boolean anyOut = false;
        for (InventoryItemDto item : staples) {
          itemIds.add(item.id());
          if (item.ingredientMappingKey() != null) {
            mappingKeys.add(item.ingredientMappingKey());
          }
          anyOut = anyOut || item.status() == StapleStatus.OUT;
        }
        BigDecimal lowestStockRatio = anyOut ? RATIO_OUT : RATIO_LOW;
        publish(
            new StapleReplenishmentNeededEvent(
                userId, itemIds, mappingKeys, lowestStockRatio, UUID.randomUUID(), now));
        dispatchLogRepository.save(
            StapleReplenishmentDispatchLog.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .scanDate(today)
                .firedAt(now)
                .itemCount(itemIds.size())
                .build());
        fired++;
      } catch (RuntimeException e) {
        log.error("staple-replenishment scan failed for userId={}", userId, e);
      }
    }
    log.info("staple-replenishment scan complete: firedForUsers={} scanDate={}", fired, today);
    return fired;
  }
}
