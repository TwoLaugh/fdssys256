package com.example.mealprep.ops;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.ai.api.AiAdminGuard;
import com.example.mealprep.ops.api.controller.AdminStatusController;
import com.example.mealprep.ops.api.dto.AdminStatusDto;
import com.example.mealprep.ops.domain.service.AdminStatusService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Swagger-contract slice test for {@code GET /api/v1/admin/status} (capability C-G-032). Mirrors
 * {@code AdminAiControllerIT}: a {@code @WebMvcTest} slice with the OpenAPI validator wired in and
 * the admin gate mocked (no-op) so the tests focus on the response/OpenAPI contract.
 */
@WebMvcTest(
    controllers = AdminStatusController.class,
    excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@ActiveProfiles("test")
@Import(OpenApiValidatorConfig.class)
class AdminStatusControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @MockBean private AdminStatusService statusService;
  @MockBean private AiAdminGuard adminGuard;

  @Test
  void status_returns200_andMatchesContract_whenDbUpAndCallsPresent() throws Exception {
    given(statusService.currentStatus())
        .willReturn(
            new AdminStatusDto(
                "UP",
                Instant.parse("2026-05-31T12:00:00Z"),
                true,
                Instant.parse("2026-05-31T11:59:00Z"),
                Instant.parse("2026-05-31T11:30:00Z"),
                new BigDecimal("1234.56")));

    mvc.perform(get("/api/v1/admin/status"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status", is("UP")))
        .andExpect(jsonPath("$.dbConnected", is(true)))
        .andExpect(jsonPath("$.aiMonthToDatePence", is(1234.56)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void status_returns200_andMatchesContract_whenDegradedAndNoCallsYet() throws Exception {
    // Null timestamps (no AI/USDA call yet) + DEGRADED must still round-trip the spec.
    given(statusService.currentStatus())
        .willReturn(
            new AdminStatusDto(
                "DEGRADED",
                Instant.parse("2026-05-31T12:00:00Z"),
                false,
                null,
                null,
                BigDecimal.ZERO));

    mvc.perform(get("/api/v1/admin/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("DEGRADED")))
        .andExpect(jsonPath("$.dbConnected", is(false)))
        // Jackson includes nulls by default; the spec marks these nullable, so an explicit null
        // must still validate.
        .andExpect(jsonPath("$.lastAiCallAt").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.lastUsdaCallAt").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void status_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin privileges required."))
        .when(adminGuard)
        .requireAdmin();

    mvc.perform(get("/api/v1/admin/status"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }
}
