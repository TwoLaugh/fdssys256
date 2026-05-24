package com.example.mealprep.preference;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.controller.TasteProfileController;
import com.example.mealprep.preference.api.dto.RollbackTasteProfileRequest;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.exception.TasteProfileVersionNotFoundException;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer + OpenAPI contract test for {@link TasteProfileController}. Loads only the web slice
 * (no Docker), and validates the request/response against {@code openapi/openapi.yaml} via the
 * {@code openApi()} matcher — drift in the (large) {@code TasteProfileDocument} schema fails the
 * test. The data path is exercised by {@code TasteProfileFlowIT}.
 */
@WebMvcTest(
    controllers = TasteProfileController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class TasteProfileControllerWebTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;

  @MockBean private TasteProfileQueryService queryService;
  @MockBean private TasteProfileUpdateService updateService;
  @MockBean private CurrentUserResolver currentUserResolver;

  private static TasteProfileDto sampleDto(UUID userId, int documentVersion) {
    TasteProfileDocument doc = TasteProfileDocument.empty(LocalDate.parse("2026-05-20"));
    return new TasteProfileDto(
        UUID.randomUUID(),
        userId,
        doc,
        documentVersion,
        null,
        0,
        null,
        null,
        TasteVectorStatus.PENDING,
        0L,
        Instant.parse("2026-05-20T10:00:00Z"),
        Instant.parse("2026-05-20T10:00:00Z"));
  }

  @Test
  void get_returns200_andMatchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(queryService.getTasteProfile(userId)).willReturn(Optional.of(sampleDto(userId, 1)));

    mvc.perform(get("/api/v1/preferences/taste-profile"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.userId", is(userId.toString())))
        .andExpect(jsonPath("$.documentVersion", is(1)))
        .andExpect(jsonPath("$.tasteVectorStatus", is("PENDING")))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void get_returns404_whenProfileMissing_matchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(queryService.getTasteProfile(userId)).willReturn(Optional.empty());

    mvc.perform(get("/api/v1/preferences/taste-profile"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status", is(404)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void put_returns200_andMatchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(updateService.applyManualOverride(eq(userId), any(), eq(userId)))
        .willReturn(sampleDto(userId, 2));

    String body =
        objectMapper.writeValueAsString(
            new com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest(
                TasteProfileDocument.empty(LocalDate.parse("2026-05-20")), 1L));

    mvc.perform(
            put("/api/v1/preferences/taste-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentVersion", is(2)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void refreshNow_returns202_andMatchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(updateService.triggerRefresh(eq(userId), any(), eq(userId), any()))
        .willReturn(sampleDto(userId, 1));

    mvc.perform(
            post("/api/v1/preferences/taste-profile/refresh-now")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.documentVersion", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void rollback_returns200_andMatchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(updateService.rollbackTasteProfile(eq(userId), eq(12), eq(7L), eq(userId)))
        .willReturn(sampleDto(userId, 16));

    String body = objectMapper.writeValueAsString(new RollbackTasteProfileRequest(12, 7L));

    mvc.perform(
            post("/api/v1/preferences/taste-profile/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentVersion", is(16)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void rollback_returns409_whenExpectedVersionStale_matchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(updateService.rollbackTasteProfile(eq(userId), eq(12), eq(0L), eq(userId)))
        .willThrow(
            new org.springframework.orm.ObjectOptimisticLockingFailureException(
                com.example.mealprep.preference.domain.entity.TasteProfile.class,
                UUID.randomUUID()));

    String body = objectMapper.writeValueAsString(new RollbackTasteProfileRequest(12, 0L));

    mvc.perform(
            post("/api/v1/preferences/taste-profile/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status", is(409)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void rollback_returns404_whenTargetVersionMissing_matchesOpenApiSchema() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));
    given(updateService.rollbackTasteProfile(eq(userId), eq(99), eq(0L), eq(userId)))
        .willThrow(new TasteProfileVersionNotFoundException(userId, 99));

    String body = objectMapper.writeValueAsString(new RollbackTasteProfileRequest(99, 0L));

    mvc.perform(
            post("/api/v1/preferences/taste-profile/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status", is(404)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void rollback_returns400_whenTargetVersionBelowMin() throws Exception {
    UUID userId = UUID.randomUUID();
    given(currentUserResolver.currentUserId()).willReturn(Optional.of(userId));

    // targetDocumentVersion 0 violates @Min(1) → 400 before the service is touched.
    String body = objectMapper.writeValueAsString(new RollbackTasteProfileRequest(0, 0L));

    mvc.perform(
            post("/api/v1/preferences/taste-profile/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    given(currentUserResolver.currentUserId()).willReturn(Optional.empty());

    mvc.perform(get("/api/v1/preferences/taste-profile")).andExpect(status().isUnauthorized());
  }
}
