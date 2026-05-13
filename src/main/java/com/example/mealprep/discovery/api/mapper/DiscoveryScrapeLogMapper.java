package com.example.mealprep.discovery.api.mapper;

import com.example.mealprep.discovery.api.dto.DiscoveryScrapeLogEntryDto;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for {@link DiscoveryScrapeLog}. */
@Mapper(componentModel = "spring")
public interface DiscoveryScrapeLogMapper {

  default DiscoveryScrapeLogEntryDto toDto(DiscoveryScrapeLog entity) {
    if (entity == null) {
      return null;
    }
    return new DiscoveryScrapeLogEntryDto(
        entity.getId(),
        entity.getJobId(),
        entity.getSourceKey(),
        entity.getCandidateUrl(),
        entity.getCanonicalUrl(),
        entity.getStatus(),
        entity.getHttpStatusCode(),
        entity.getRobotsTxtOutcome(),
        entity.getLatencyMs(),
        entity.getContentFingerprint(),
        entity.getExtractionMethod(),
        entity.getExtractionConfidence(),
        entity.getRecipeId(),
        entity.getSkipReason(),
        entity.getErrorClass(),
        entity.getErrorMessage(),
        entity.getOccurredAt());
  }

  default List<DiscoveryScrapeLogEntryDto> toDtos(List<DiscoveryScrapeLog> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<DiscoveryScrapeLogEntryDto> out = new ArrayList<>(entities.size());
    for (DiscoveryScrapeLog e : entities) {
      out.add(toDto(e));
    }
    return out;
  }
}
