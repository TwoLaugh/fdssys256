package com.example.mealprep.preference.api.mapper;

import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.domain.entity.PreferenceArchiveEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;

/** Entity → DTO mapping for the preference archive. */
@Mapper(componentModel = "spring")
public interface PreferenceArchiveMapper {

  default PreferenceArchiveEntryDto toDto(PreferenceArchiveEntry entity) {
    if (entity == null) {
      return null;
    }
    return new PreferenceArchiveEntryDto(
        entity.getId(),
        entity.getUserId(),
        entity.getFieldPath(),
        entity.getItemKey(),
        entity.getItemPayload(),
        entity.getEvidenceCount(),
        entity.getLastSignalAt(),
        entity.getArchivedAt(),
        entity.getArchivedReason(),
        entity.getRePromotedAt());
  }

  default List<PreferenceArchiveEntryDto> toDtos(List<PreferenceArchiveEntry> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<PreferenceArchiveEntryDto> result = new ArrayList<>(entities.size());
    for (PreferenceArchiveEntry entity : entities) {
      result.add(toDto(entity));
    }
    return result;
  }
}
