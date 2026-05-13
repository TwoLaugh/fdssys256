package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.api.dto.ClarificationOptionDto;
import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.api.mapper.ClarificationQueryMapper;
import com.example.mealprep.feedback.api.mapper.FeedbackEntryMapper;
import com.example.mealprep.feedback.api.mapper.MisclassificationCorrectionMapper;
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct round-trip checks for the four feedback mappers. Pure unit test — no Spring context
 * boot, no Testcontainers. Instantiates the generated MapStruct {@code Impl} classes directly via
 * {@link Mappers#getMapper}; for the two abstract-class mappers the protected dependency fields are
 * set via reflection in {@link #setUp}.
 */
class FeedbackMapperTest {

  private final RoutingLogMapper routingLogMapper = Mappers.getMapper(RoutingLogMapper.class);
  private final MisclassificationCorrectionMapper correctionMapper =
      Mappers.getMapper(MisclassificationCorrectionMapper.class);
  private final FeedbackEntryMapper feedbackEntryMapper =
      Mappers.getMapper(FeedbackEntryMapper.class);
  private final ClarificationQueryMapper clarificationQueryMapper =
      Mappers.getMapper(ClarificationQueryMapper.class);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws Exception {
    injectField(feedbackEntryMapper, "routingLogMapper", routingLogMapper);
    injectField(clarificationQueryMapper, "objectMapper", objectMapper);
  }

  private static void injectField(Object target, String fieldName, Object value) throws Exception {
    Class<?> klass = target.getClass();
    while (klass != null) {
      try {
        Field f = klass.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
        return;
      } catch (NoSuchFieldException ignored) {
        klass = klass.getSuperclass();
      }
    }
    throw new IllegalStateException("field not found: " + fieldName);
  }

  @Test
  void routingLogMapper_withNonNullDestinationResult_surfacesRawJsonNode() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "salt");
    RoutingLogEntry entity = FeedbackTestData.routingLogEntry(parent);

    RoutingDecisionDto dto = routingLogMapper.toDto(entity);

    assertThat(dto.id()).isEqualTo(entity.getId());
    assertThat(dto.destination()).isEqualTo(Destination.RECIPE);
    assertThat(dto.confidence()).isEqualByComparingTo(entity.getConfidence());
    assertThat(dto.destinationResult()).isInstanceOf(JsonNode.class);
    assertThat(((JsonNode) dto.destinationResult()).get("ackId")).isNotNull();
  }

  @Test
  void routingLogMapper_withNullDestinationResult_returnsNull() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "salt");
    RoutingLogEntry entity = FeedbackTestData.routingLogEntry(parent);
    entity.setDestinationResultJson(null);

    RoutingDecisionDto dto = routingLogMapper.toDto(entity);

    assertThat(dto.destinationResult()).isNull();
  }

  @Test
  void feedbackEntryMapper_populatesRoutes_andLeavesPendingClarificationNull() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "make it lighter");
    RoutingLogEntry log = FeedbackTestData.routingLogEntry(parent);
    parent.getRoutingLog().add(log);
    parent.setCreatedAt(Instant.now());
    parent.setUpdatedAt(Instant.now());

    FeedbackEntryDto dto = feedbackEntryMapper.toDto(parent);

    assertThat(dto.id()).isEqualTo(parent.getId());
    assertThat(dto.context()).isNotNull();
    assertThat(dto.context().screen()).isEqualTo(parent.getUiContext().screen());
    assertThat(dto.routes()).hasSize(1);
    assertThat(dto.routes().get(0).destination()).isEqualTo(Destination.RECIPE);
    assertThat(dto.pendingClarificationQueryId()).isNull();
  }

  @Test
  void clarificationQueryMapper_deserialisesOptionsList() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "ambiguous");
    ClarificationQuery query = FeedbackTestData.clarificationQuery(parent);
    query.setCreatedAt(Instant.now());

    ClarificationQueryDto dto = clarificationQueryMapper.toDto(query);

    assertThat(dto.id()).isEqualTo(query.getId());
    assertThat(dto.options()).hasSize(2);
    ClarificationOptionDto first = dto.options().get(0);
    assertThat(first.destination()).isEqualTo(Destination.RECIPE);
    assertThat(first.snippet()).isEqualTo("make the lasagne lighter");
    assertThat(dto.feedbackEntryId()).isEqualTo(parent.getId());
  }

  @Test
  void misclassificationCorrectionMapper_roundTripsAllFields() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "wrong dest");
    MisclassificationCorrection entity =
        FeedbackTestData.misclassificationCorrection(parent, UUID.randomUUID(), parent.getUserId());
    entity.setCreatedAt(Instant.now());

    MisclassificationCorrectionDto dto = correctionMapper.toDto(entity);

    assertThat(dto.id()).isEqualTo(entity.getId());
    assertThat(dto.correctedDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(dto.originalDestination()).isEqualTo(Destination.RECIPE);
    assertThat(dto.replayStatus()).isEqualTo(entity.getReplayStatus());
    assertThat(dto.feedbackEntryId()).isEqualTo(parent.getId());
  }
}
