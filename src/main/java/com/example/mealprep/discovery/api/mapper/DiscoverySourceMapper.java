package com.example.mealprep.discovery.api.mapper;

import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity ↔ DTO mapping for {@link DiscoverySource}. {@code crawlConfig} (JSONB opaque payload) and
 * {@code qualityScore} are deliberately not exposed; the DTO is the admin / debug read surface
 * only.
 */
@Mapper(componentModel = "spring")
public interface DiscoverySourceMapper {

  default DiscoverySourceDto toDto(DiscoverySource entity) {
    if (entity == null) {
      return null;
    }
    return new DiscoverySourceDto(
        entity.getId(),
        entity.getSourceKey(),
        entity.getDisplayName(),
        entity.getKind(),
        entity.getBaseUrl(),
        entity.isEnabled(),
        entity.getRequestsPerMinute(),
        entity.getRequestsPerDay(),
        entity.isRespectRobotsTxt(),
        entity.getUserAgent(),
        entity.getFailureStreak(),
        entity.getLastFailureAt(),
        entity.getLastSuccessAt(),
        entity.getNotes(),
        entity.getOptimisticVersion());
  }

  default List<DiscoverySourceDto> toDtos(List<DiscoverySource> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<DiscoverySourceDto> out = new ArrayList<>(entities.size());
    for (DiscoverySource e : entities) {
      out.add(toDto(e));
    }
    return out;
  }
}
