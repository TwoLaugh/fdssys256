package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.spi.Destination;
import java.math.BigDecimal;

/**
 * Spring Data projection for the {@code RoutingLogRepository.aggregateByDestination} JPQL group-by.
 * Package-private — only the repo and the future quality-monitoring service (feedback-01g) consume
 * this. Interface projection (vs. record / constructor projection) keeps the projection trivially
 * proxyable by Spring Data without a dedicated SELECT NEW clause.
 */
interface DestinationRollupRow {
  Destination getDestination();

  long getCount();

  BigDecimal getAvgConfidence();
}
