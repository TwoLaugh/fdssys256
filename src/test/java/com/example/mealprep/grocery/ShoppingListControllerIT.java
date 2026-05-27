package com.example.mealprep.grocery;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.grocery.api.dto.RecalculateShoppingListRequest;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.TriggerKind;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP flow over the Tier-1 shopping-list endpoints (grocery-01b). Real Postgres (Testcontainers);
 * only {@link PlanQueryService} is mocked (single-interface impl, no multi-interface-MockBean
 * eviction) so {@code /recalculate} can drive the real calculator without seeding planner/recipe
 * data. Read + export endpoints exercise a directly-seeded list. Covers 200 / 400 / 404 + OpenAPI
 * validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class ShoppingListControllerIT {

  private static final String BASE = "/api/v1/grocery/shopping-lists";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  // Single-interface impl (PlannerServiceImpl implements only PlanQueryService) → safe to mock.
  @MockBean private PlanQueryService planQueryService;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("sl-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userId), cookie);
  }

  private UUID seedList(UUID userId, UUID planId, int generation) {
    UUID listId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO shopping_lists (id, user_id, household_id, plan_id, plan_generation,"
            + " generated_at, estimated_total_currency, stale_ingredient_count,"
            + " pantry_tracking_enabled, version, created_at, updated_at)"
            + " VALUES (?, ?, NULL, ?, ?, now(), 'GBP', 0, false, 0, now(), now())",
        listId,
        userId,
        planId,
        generation);
    jdbcTemplate.update(
        "INSERT INTO shopping_list_lines (id, shopping_list_id, ingredient_mapping_key,"
            + " display_name, requested_quantity, requested_unit, line_type, is_stale_estimate,"
            + " fulfilment_status, created_at, updated_at)"
            + " VALUES (?, ?, 'flour', 'Flour', 1.000, 'kg', 'PLANNED_DEMAND', false, 'UNFILLED',"
            + " now(), now())",
        UUID.randomUUID(),
        listId);
    return listId;
  }

  private PlanDto plan(UUID planId, int generation) {
    return new PlanDto(
        planId,
        null,
        LocalDate.of(2026, 6, 1),
        generation,
        null,
        PlanStatus.GENERATED,
        TriggerKind.USER_INITIATED,
        null,
        false,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        0L,
        Instant.now(),
        Instant.now());
  }

  // ---------------- auth ----------------

  @Test
  void getById_anonymous_returns401() throws Exception {
    mvc.perform(get(BASE + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
  }

  // ---------------- get by id ----------------

  @Test
  void getById_seededList_returns200WithLines() throws Exception {
    AuthedUser user = registerUser();
    UUID planId = UUID.randomUUID();
    UUID listId = seedList(user.userId(), planId, 1);

    mvc.perform(get(BASE + "/" + listId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(listId.toString()))
        .andExpect(jsonPath("$.planGeneration").value(1))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].ingredientMappingKey").value("flour"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getById_missing_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get(BASE + "/" + UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- current by plan ----------------

  @Test
  void current_seededList_returns200() throws Exception {
    AuthedUser user = registerUser();
    UUID planId = UUID.randomUUID();
    seedList(user.userId(), planId, 1);

    mvc.perform(get(BASE + "/current").param("planId", planId.toString()).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").value(planId.toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void current_noListForPlan_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get(BASE + "/current")
                .param("planId", UUID.randomUUID().toString())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- history ----------------

  @Test
  void history_returnsPagedLists() throws Exception {
    AuthedUser user = registerUser();
    seedList(user.userId(), UUID.randomUUID(), 1);

    mvc.perform(get(BASE + "/history").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- recalculate ----------------

  @Test
  void recalculate_unknownPlan_returns404() throws Exception {
    AuthedUser user = registerUser();
    UUID planId = UUID.randomUUID();
    org.mockito.Mockito.when(planQueryService.getPlanById(planId)).thenReturn(Optional.empty());

    mvc.perform(
            post(BASE + "/recalculate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new RecalculateShoppingListRequest(planId, null))))
        .andExpect(status().isNotFound());
  }

  @Test
  void recalculate_missingPlanId_returns400() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post(BASE + "/recalculate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void recalculate_emptyPlan_returns200WithRenderableList() throws Exception {
    AuthedUser user = registerUser();
    UUID planId = UUID.randomUUID();
    // An empty-days plan → no recipe reads; no staples for a fresh user → an empty-but-valid list.
    org.mockito.Mockito.when(planQueryService.getPlanById(planId))
        .thenReturn(Optional.of(plan(planId, 1)));

    mvc.perform(
            post(BASE + "/recalculate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new RecalculateShoppingListRequest(planId, 1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").value(planId.toString()))
        .andExpect(jsonPath("$.planGeneration").value(1))
        .andExpect(jsonPath("$.lines.length()").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void recalculate_sameGeneration_isIdempotent_returnsExisting() throws Exception {
    AuthedUser user = registerUser();
    UUID planId = UUID.randomUUID();
    org.mockito.Mockito.when(planQueryService.getPlanById(planId))
        .thenReturn(Optional.of(plan(planId, 1)));

    MvcResult first =
        mvc.perform(
                post(BASE + "/recalculate")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RecalculateShoppingListRequest(planId, 1))))
            .andExpect(status().isOk())
            .andReturn();
    String firstId =
        objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

    MvcResult second =
        mvc.perform(
                post(BASE + "/recalculate")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RecalculateShoppingListRequest(planId, 1))))
            .andExpect(status().isOk())
            .andReturn();
    String secondId =
        objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

    org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId); // same row
  }

  // ---------------- export ----------------

  @Test
  void export_html_returns200() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId(), UUID.randomUUID(), 1);

    mvc.perform(
            get(BASE + "/" + listId + "/export")
                .param("format", "PRINTABLE_HTML")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("PRINTABLE_HTML"))
        .andExpect(jsonPath("$.content").isNotEmpty())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void export_csv_returns200() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId(), UUID.randomUUID(), 1);

    mvc.perform(get(BASE + "/" + listId + "/export").param("format", "CSV").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.format").value("CSV"));
  }

  @Test
  void export_missingList_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get(BASE + "/" + UUID.randomUUID() + "/export")
                .param("format", "MARKDOWN")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }
}
