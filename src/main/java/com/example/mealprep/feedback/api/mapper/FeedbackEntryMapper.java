package com.example.mealprep.feedback.api.mapper;

import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.domain.document.UiContextDocument;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Entity ↔ DTO mapping for {@link FeedbackEntry}. {@code routes} is populated from the entity's
 * lazy {@code routingLog} list via {@link RoutingLogMapper}; {@code pendingClarificationQueryId} is
 * deliberately left null — that field needs a separate repository lookup and the service layer
 * fills it (feedback-01b).
 *
 * <p>Abstract-class form with field-injected {@link RoutingLogMapper} (Spring populates after
 * MapStruct generates the no-arg subclass). Avoids the constructor-injection pitfall where the
 * generated {@code MapperImpl} omits a matching constructor.
 */
@Mapper(componentModel = "spring")
public abstract class FeedbackEntryMapper {

  @Autowired protected RoutingLogMapper routingLogMapper;

  public FeedbackEntryDto toDto(FeedbackEntry entity) {
    if (entity == null) {
      return null;
    }
    return new FeedbackEntryDto(
        entity.getId(),
        entity.getUserId(),
        entity.getText(),
        toUiContextDto(entity.getUiContext()),
        entity.getSubmissionStatus(),
        entity.getClassificationAttempts(),
        entity.getLastClassifiedAt(),
        entity.getTraceId(),
        routingLogMapper.toDtos(entity.getRoutingLog()),
        null, // pendingClarificationQueryId — filled by service layer (feedback-01b)
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  public List<FeedbackEntryDto> toDtos(List<FeedbackEntry> entities) {
    if (entities == null || entities.isEmpty()) {
      return Collections.emptyList();
    }
    List<FeedbackEntryDto> out = new ArrayList<>(entities.size());
    for (FeedbackEntry e : entities) {
      out.add(toDto(e));
    }
    return out;
  }

  UiContextDto toUiContextDto(UiContextDocument doc) {
    if (doc == null) {
      return null;
    }
    return new UiContextDto(
        doc.screen(),
        doc.recipeId(),
        doc.recipeVersion(),
        doc.mealSlotId(),
        doc.planId(),
        doc.referenceDate());
  }
}
