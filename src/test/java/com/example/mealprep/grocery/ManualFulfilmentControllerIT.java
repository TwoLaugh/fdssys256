package com.example.mealprep.grocery;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.grocery.api.dto.BulkMarkBoughtRequest;
import com.example.mealprep.grocery.api.dto.MarkBoughtRequest;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP flow over the Tier-2 manual-fulfilment endpoints (grocery-01d). Real Postgres
 * (Testcontainers); the inventory write runs through the real provisions {@code applyGroceryOrder}.
 * Seeds a list + line directly, then exercises mark-bought / bulk-mark-bought / undo across
 * 200/204/400/404/409 with OpenAPI contract validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class ManualFulfilmentControllerIT {

  private static final String BASE = "/api/v1/grocery/shopping-lists";

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
    jdbcTemplate.update("DELETE FROM provision_grocery_import_log");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("mf-" + AuthTestData.shortId());
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

  private UUID seedList(UUID userId) {
    UUID listId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO shopping_lists (id, user_id, household_id, plan_id, plan_generation,"
            + " generated_at, estimated_total_currency, stale_ingredient_count,"
            + " pantry_tracking_enabled, version, created_at, updated_at)"
            + " VALUES (?, ?, NULL, ?, 1, now(), 'GBP', 0, false, 0, now(), now())",
        listId,
        userId,
        UUID.randomUUID());
    return listId;
  }

  private UUID seedLine(UUID listId, String key, String displayName, Integer estimatedLinePence) {
    UUID lineId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO shopping_list_lines (id, shopping_list_id, ingredient_mapping_key,"
            + " display_name, requested_quantity, requested_unit, line_type, estimated_line_pence,"
            + " is_stale_estimate, fulfilment_status, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, 1.000, 'kg', 'PLANNED_DEMAND', ?, false, 'UNFILLED', now(),"
            + " now())",
        lineId,
        listId,
        key,
        displayName,
        estimatedLinePence);
    return lineId;
  }

  private String markBoughtUrl(UUID listId, UUID lineId) {
    return BASE + "/" + listId + "/lines/" + lineId + "/mark-bought";
  }

  private long inventoryCount(UUID userId) {
    Long n =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory WHERE user_id = ?", Long.class, userId);
    return n == null ? 0L : n;
  }

  // ---------------- auth ----------------

  @Test
  void markBought_anonymous_returns401() throws Exception {
    mvc.perform(
            post(markBoughtUrl(UUID.randomUUID(), UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- mark-bought ----------------

  @Test
  void markBought_withPrice_returns200_landsInventory_setsBoughtStatus() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "flour", "Flour", 200);

    MarkBoughtRequest req =
        new MarkBoughtRequest(lineId, new BigDecimal("1.000"), "kg", 250, "tesco", null);

    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shoppingListLineId").value(lineId.toString()))
        .andExpect(jsonPath("$.newStatus").value("BOUGHT"))
        .andExpect(jsonPath("$.priceObservationId").isNotEmpty())
        .andExpect(jsonPath("$.inventoryItemId").isNotEmpty())
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(inventoryCount(user.userId())).isEqualTo(1L);
  }

  @Test
  void markBought_noPrice_returns200_noObservation_stillLandsInventory() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "rice", "Rice", null);

    MarkBoughtRequest req =
        new MarkBoughtRequest(lineId, new BigDecimal("2.000"), "kg", null, null, null);

    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.newStatus").value("BOUGHT"))
        .andExpect(jsonPath("$.priceObservationId").doesNotExist())
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(inventoryCount(user.userId())).isEqualTo(1L);
  }

  @Test
  void markBought_overMark_returns200_withNote() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "eggs", "Eggs", null);

    MarkBoughtRequest req =
        new MarkBoughtRequest(lineId, new BigDecimal("3.000"), "kg", null, null, null);

    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").isNotEmpty())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void markBought_invalidUnit_returns400() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "flour", "Flour", null);

    MarkBoughtRequest req =
        new MarkBoughtRequest(lineId, new BigDecimal("1.000"), "ounces", null, null, null);

    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void markBought_missingLine_returns404() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID missing = UUID.randomUUID();

    MarkBoughtRequest req =
        new MarkBoughtRequest(missing, new BigDecimal("1.000"), "kg", 100, null, null);

    mvc.perform(
            post(markBoughtUrl(listId, missing))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNotFound());
  }

  @Test
  void markBought_alreadyBought_returns409() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "flour", "Flour", 200);

    MarkBoughtRequest req =
        new MarkBoughtRequest(lineId, new BigDecimal("1.000"), "kg", 250, "tesco", null);

    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());

    // Second mark on the now-BOUGHT line → 409.
    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  // ---------------- bulk-mark-bought ----------------

  @Test
  void bulkMarkBought_withTotal_returns200_oneResultPerLine() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID a = seedLine(listId, "flour", "Flour", 100);
    UUID b = seedLine(listId, "rice", "Rice", 200);

    BulkMarkBoughtRequest req =
        new BulkMarkBoughtRequest(listId, List.of(a, b), 900, "tesco", null);

    mvc.perform(
            post(BASE + "/" + listId + "/bulk-mark-bought")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].newStatus").value("BOUGHT"))
        .andExpect(jsonPath("$[1].newStatus").value("BOUGHT"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void bulkMarkBought_emptyLineSet_returns400() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());

    String body =
        "{\"shoppingListId\":\""
            + listId
            + "\",\"shoppingListLineIds\":[],\"totalSpendPence\":100}";

    mvc.perform(
            post(BASE + "/" + listId + "/bulk-mark-bought")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkMarkBought_missingLine_returns404() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID present = seedLine(listId, "flour", "Flour", 100);
    UUID missing = UUID.randomUUID();

    BulkMarkBoughtRequest req =
        new BulkMarkBoughtRequest(listId, List.of(present, missing), 200, "tesco", null);

    mvc.perform(
            post(BASE + "/" + listId + "/bulk-mark-bought")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNotFound());
  }

  // ---------------- undo-mark-bought ----------------

  @Test
  void undoMarkBought_boughtLine_returns204_revertsStatus() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "flour", "Flour", 200);

    MarkBoughtRequest req =
        new MarkBoughtRequest(lineId, new BigDecimal("1.000"), "kg", 250, "tesco", null);
    mvc.perform(
            post(markBoughtUrl(listId, lineId))
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());

    mvc.perform(
            post(BASE + "/" + listId + "/lines/" + lineId + "/undo-mark-bought")
                .cookie(user.cookie()))
        .andExpect(status().isNoContent());

    String status =
        jdbcTemplate.queryForObject(
            "SELECT fulfilment_status FROM shopping_list_lines WHERE id = ?", String.class, lineId);
    assertThat(status).isEqualTo("UNFILLED");

    // The append-only observation is NOT deleted (the original + a compensating row remain).
    Long observations =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grocery_price_history WHERE shopping_list_line_id = ?",
            Long.class,
            lineId);
    assertThat(observations).isGreaterThanOrEqualTo(1L);
  }

  @Test
  void undoMarkBought_notBought_returns409() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());
    UUID lineId = seedLine(listId, "flour", "Flour", 200); // UNFILLED

    mvc.perform(
            post(BASE + "/" + listId + "/lines/" + lineId + "/undo-mark-bought")
                .cookie(user.cookie()))
        .andExpect(status().isConflict());
  }

  @Test
  void undoMarkBought_missingLine_returns404() throws Exception {
    AuthedUser user = registerUser();
    UUID listId = seedList(user.userId());

    mvc.perform(
            post(BASE + "/" + listId + "/lines/" + UUID.randomUUID() + "/undo-mark-bought")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }
}
