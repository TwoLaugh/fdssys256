package com.example.mealprep.preference;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.controller.PreferenceArchiveController;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.service.PreferenceArchiveQueryService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer + OpenAPI contract test for {@link PreferenceArchiveController}. Loads only the web
 * slice (no Docker) and validates request/response against {@code openapi/openapi.yaml} via the
 * {@code openApi()} matcher. The data path is exercised by {@code PreferenceArchiveFlowIT}.
 */
@WebMvcTest(
    controllers = PreferenceArchiveController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class PreferenceArchiveControllerWebTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;

  @MockBean private PreferenceArchiveQueryService queryService;
  @MockBean private CurrentUserResolver currentUserResolver;

  private PreferenceArchiveEntryDto sampleDto(UUID userId) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("item", "chicken thighs");
    payload.put("evidenceCount", 4);
    return new PreferenceArchiveEntryDto(
        UUID.randomUUID(),
        userId,
        "ingredientPreferences.favourites",
        "chicken thighs",
        payload,
        4,
        LocalDate.parse("2026-05-01"),
        Instant.parse("2026-05-10T10:00:00Z"),
        ArchiveReason.LOW_EVIDENCE,
        null);
  }

  @Test
  void getArchive_returns200_andMatchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    Page<PreferenceArchiveEntryDto> page =
        new PageImpl<>(List.of(sampleDto(userId)), PageRequest.of(0, 20), 1L);
    given(queryService.getArchive(eq(userId), any())).willReturn(page);

    mvc.perform(get("/api/v1/preferences/archive"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content[0].itemKey", is("chicken thighs")))
        .andExpect(jsonPath("$.content[0].archivedReason", is("LOW_EVIDENCE")))
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getArchive_withFieldPathPrefix_delegatesToFieldFilteredQuery() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    Page<PreferenceArchiveEntryDto> page =
        new PageImpl<>(List.of(sampleDto(userId)), PageRequest.of(0, 20), 1L);
    given(queryService.getArchiveForField(eq(userId), eq("ingredientPreferences"), any()))
        .willReturn(page);

    mvc.perform(
            get("/api/v1/preferences/archive").param("fieldPathPrefix", "ingredientPreferences"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].fieldPath", is("ingredientPreferences.favourites")))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getArchive_emptyArchive_returns200_emptyContent() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(queryService.getArchive(eq(userId), any()))
        .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

    mvc.perform(get("/api/v1/preferences/archive"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()", is(0)))
        .andExpect(jsonPath("$.totalElements", is(0)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void activeCount_returns200_andMatchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(queryService.countActiveEntries(userId)).willReturn(3L);

    mvc.perform(get("/api/v1/preferences/archive/active-count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count", is(3)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getArchive_returns401_whenAnonymous() throws Exception {
    given(currentUserResolver.currentUserId()).willReturn(Optional.empty());

    mvc.perform(get("/api/v1/preferences/archive")).andExpect(status().isUnauthorized());
  }

  @Test
  void activeCount_returns401_whenAnonymous() throws Exception {
    given(currentUserResolver.currentUserId()).willReturn(Optional.empty());

    mvc.perform(get("/api/v1/preferences/archive/active-count"))
        .andExpect(status().isUnauthorized());
  }
}
