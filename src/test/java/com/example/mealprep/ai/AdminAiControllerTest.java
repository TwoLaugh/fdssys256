package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.api.controller.AdminAiController;
import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.api.dto.CostSummaryDto;
import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.domain.service.AdminAiQueryService;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Pure-unit test for {@link AdminAiController}. Baseline NO_COVERAGE on every method's
 * NullReturnVals — controller methods must return their delegate's value, not null.
 *
 * <p>Bean validation (@Min / @Max) is enforced by Spring at runtime, not in this direct-call shape,
 * but the dispatch wiring itself is what Pitest mutates.
 */
class AdminAiControllerTest {

  private final AdminAiQueryService queryService = mock(AdminAiQueryService.class);
  private final PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
  private final AdminAiController controller =
      new AdminAiController(queryService, promptTemplateService);

  @Test
  void getCostSummary_delegatesAndReturnsResult() {
    CostSummaryDto dto = new CostSummaryDto(24, 0L, 0L, List.of());
    when(queryService.getCostSummary(24)).thenReturn(dto);
    assertThat(controller.getCostSummary(24)).isSameAs(dto);
    verify(queryService).getCostSummary(24);
  }

  @Test
  void getCallLog_buildsPageRequest_andDelegates() {
    Page<AiCallLogDto> empty = new PageImpl<>(List.of());
    when(queryService.getCallLog(any(), any(), any())).thenReturn(empty);

    UUID userId = UUID.randomUUID();
    Page<AiCallLogDto> result =
        controller.getCallLog(2, 15, TaskType.FEEDBACK_CLASSIFICATION, userId);
    assertThat(result).isSameAs(empty);

    ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
    verify(queryService)
        .getCallLog(eq(TaskType.FEEDBACK_CLASSIFICATION), eq(userId), pageCap.capture());
    assertThat(pageCap.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pageCap.getValue().getPageSize()).isEqualTo(15);
  }

  @Test
  void getCallLog_nullFilters_passThrough() {
    when(queryService.getCallLog(eq(null), eq(null), any())).thenReturn(new PageImpl<>(List.of()));
    controller.getCallLog(0, 20, null, null);
    verify(queryService).getCallLog(eq(null), eq(null), any());
  }

  @Test
  void listPromptTemplates_buildsPageRequest_andDelegates() {
    Page<PromptTemplateDto> empty = new PageImpl<>(List.of());
    when(promptTemplateService.listAll(any())).thenReturn(empty);

    Page<PromptTemplateDto> result = controller.listPromptTemplates(3, 10);
    assertThat(result).isSameAs(empty);
    ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
    verify(promptTemplateService).listAll(pageCap.capture());
    assertThat(pageCap.getValue().getPageNumber()).isEqualTo(3);
    assertThat(pageCap.getValue().getPageSize()).isEqualTo(10);
  }

  @Test
  void getPromptTemplate_delegatesByNameAndVersion() {
    PromptTemplateDto dto =
        new PromptTemplateDto(
            UUID.randomUUID(),
            "classify",
            2,
            ModelTier.CHEAP,
            "sys",
            "user",
            null,
            null,
            null,
            "src",
            "h",
            null);
    when(promptTemplateService.get("classify", 2)).thenReturn(dto);
    assertThat(controller.getPromptTemplate("classify", 2)).isSameAs(dto);
  }
}
