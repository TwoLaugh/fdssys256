package com.example.mealprep.provisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.provisions.api.dto.CookEventCommand;
import com.example.mealprep.provisions.api.dto.MealConsumptionCommand;
import com.example.mealprep.provisions.api.dto.RecipeIngredientUsage;
import com.example.mealprep.provisions.api.dto.StandaloneConsumptionCommand;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.event.ItemQuantityAdjustedEvent;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP-cycle behaviour spec for the cook-event + consumption flows (01g) — the three {@code
 * CookEventController} endpoints driven through the real Testcontainers stack:
 *
 * <ul>
 *   <li>{@code POST /cook-event}: FIFO-by-expiry deduction across multiple rows, audit rows, the
 *       coalesced AFTER_COMMIT {@link ItemQuantityAdjustedEvent}, exhaustion → staple {@code OUT} +
 *       {@link ItemRanOutEvent}, dedupe idempotency (caller key + auto-computed SHA key),
 *       non-strict partial underflow in the body, strict-mode 422, unit-mismatch underflow,
 *       batch-cook 422.
 *   <li>{@code POST /meal-consumption}: decrement one specific row, floor-at-zero exhaustion, 404
 *       when the row belongs to another user.
 *   <li>{@code POST /standalone-consumption}: preview (no mutation) vs confirmed deduction of the
 *       oldest-expiry row, empty-match 200-with-null-body.
 * </ul>
 *
 * <p>State is seeded straight through {@link InventoryItemRepository} (no POST → no async runner
 * racing the assertions); only the flow under test drives the controller. Events are observed via
 * an {@code AFTER_COMMIT} capture bean so we assert real published outcomes, not just status codes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, CookEventFlowIT.CookEventCaptureConfig.class})
@ActiveProfiles("test")
class CookEventFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private CookEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_cook_event_dedupe");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  /** Seed an ACTIVE quantity-tracked row for {@code key} with the given quantity + expiry. */
  private UUID seedItem(UUID userId, String key, String quantity, LocalDate expiry) {
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .name(key)
            .ingredientMappingKey(key)
            .quantity(new BigDecimal(quantity))
            .expiryDate(expiry)
            .build();
    return inventoryItemRepository.saveAndFlush(item).getId();
  }

  private UUID seedStapleItem(UUID userId, String key, String quantity, LocalDate expiry) {
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .name(key)
            .ingredientMappingKey(key)
            .quantity(new BigDecimal(quantity))
            .expiryDate(expiry)
            .isStaple(true)
            .status(StapleStatus.STOCKED)
            .build();
    return inventoryItemRepository.saveAndFlush(item).getId();
  }

  private MvcResult postCookEvent(Cookie cookie, CookEventCommand command, int expectedStatus)
      throws Exception {
    return mvc.perform(
            post("/api/v1/provisions/cook-event")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(command)))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  private double quantityOf(UUID itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT quantity FROM provision_inventory WHERE id = ?", Double.class, itemId);
  }

  private String itemStatusOf(UUID itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT item_status FROM provision_inventory WHERE id = ?", String.class, itemId);
  }

  // ---------------- POST /cook-event ----------------

  @Test
  void cookEvent_returns401_whenAnonymous() throws Exception {
    CookEventCommand cmd =
        ProvisionsTestData.cookEventCommand(
            UUID.randomUUID(), UUID.randomUUID(), "cheese:cheddar", new BigDecimal("50"), "g");
    mvc.perform(
            post("/api/v1/provisions/cook-event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void cookEvent_returns400_whenIngredientsEmpty() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "recipeId": "%s",
          "mealSlotId": "%s",
          "servingsCooked": 1,
          "ingredientsUsed": []
        }
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID());
    mvc.perform(
            post("/api/v1/provisions/cook-event")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cookEvent_deductsFifoByExpiryAcrossRows_writesAudit_publishesOneCoalescedEvent()
      throws Exception {
    AuthedUser user = registerUser();
    // Two rows for the same key; the earlier-expiry row must be drained first.
    UUID older = seedItem(user.userId(), "cheese:cheddar", "60.000", LocalDate.parse("2026-06-01"));
    UUID newer =
        seedItem(user.userId(), "cheese:cheddar", "100.000", LocalDate.parse("2026-09-01"));

    // Request 90g: drains the 60g older row to 0 then takes 30g from the newer row.
    CookEventCommand cmd =
        ProvisionsTestData.cookEventCommand(
            UUID.randomUUID(), UUID.randomUUID(), "cheese:cheddar", new BigDecimal("90.000"), "g");

    MvcResult result = postCookEvent(user.cookie(), cmd, 200);

    assertThat(quantityOf(older)).isEqualTo(0.0);
    assertThat(itemStatusOf(older)).isEqualTo("EXHAUSTED");
    assertThat(quantityOf(newer)).isEqualTo(70.0);
    assertThat(itemStatusOf(newer)).isEqualTo("ACTIVE");

    String body = result.getResponse().getContentAsString();
    // updatedItems carries the read-back of both decremented rows.
    assertThat(objectMapper.readTree(body).get("updatedItems")).hasSize(2);
    assertThat(objectMapper.readTree(body).get("exhaustedItems")).hasSize(1);
    assertThat(objectMapper.readTree(body).get("exhaustedItems").get(0).asText())
        .isEqualTo(older.toString());
    assertThat(objectMapper.readTree(body).get("underflows")).isEmpty();

    // Two quantity audit rows (one per decremented row), actor COOK_EVENT.
    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE field_changed = 'quantity' AND actor = 'COOK_EVENT'",
            Long.class);
    assertThat(auditCount).isEqualTo(2L);

    // Single coalesced AFTER_COMMIT event carrying BOTH affected ids (LLD single-event contract).
    assertThat(eventCapture.adjusted()).hasSize(1);
    ItemQuantityAdjustedEvent ev = eventCapture.adjusted().get(0);
    assertThat(ev.userId()).isEqualTo(user.userId());
    assertThat(ev.affectedItemIds()).containsExactlyInAnyOrder(older, newer);
  }

  @Test
  void cookEvent_exhaustsStapleRow_setsStapleOut_andPublishesRanOutEvent() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedStapleItem(user.userId(), "spice:cumin", "20.000", LocalDate.parse("2026-08-01"));

    CookEventCommand cmd =
        ProvisionsTestData.cookEventCommand(
            UUID.randomUUID(), UUID.randomUUID(), "spice:cumin", new BigDecimal("50.000"), "g");

    postCookEvent(user.cookie(), cmd, 200);

    assertThat(quantityOf(itemId)).isEqualTo(0.0);
    assertThat(itemStatusOf(itemId)).isEqualTo("EXHAUSTED");
    String stapleStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM provision_inventory WHERE id = ?", String.class, itemId);
    assertThat(stapleStatus).isEqualTo("OUT");

    assertThat(eventCapture.ranOut()).hasSize(1);
    ItemRanOutEvent ev = eventCapture.ranOut().get(0);
    assertThat(ev.userId()).isEqualTo(user.userId());
    assertThat(ev.ingredientMappingKey()).isEqualTo("spice:cumin");
    assertThat(ev.wasStaple()).isTrue();
    assertThat(ev.affectedItemIds()).containsExactly(itemId);
  }

  @Test
  void cookEvent_duplicateDedupeKey_isIdempotentNoOp() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "200.000", LocalDate.parse("2026-06-01"));
    UUID mealSlotId = UUID.randomUUID();

    CookEventCommand first =
        new CookEventCommand(
            UUID.randomUUID(),
            null,
            mealSlotId,
            1,
            false,
            null,
            false,
            "fixed-dedupe-key",
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("50.000"), "g")),
            null);
    postCookEvent(user.cookie(), first, 200);
    assertThat(quantityOf(itemId)).isEqualTo(150.0);

    // Replay with the SAME (mealSlotId, dedupeKey): no-op, empty result, no further deduction.
    MvcResult replay = postCookEvent(user.cookie(), first, 200);
    String body = replay.getResponse().getContentAsString();
    assertThat(objectMapper.readTree(body).get("updatedItems")).isEmpty();
    assertThat(objectMapper.readTree(body).get("exhaustedItems")).isEmpty();
    assertThat(quantityOf(itemId)).isEqualTo(150.0);

    Long dedupeRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_cook_event_dedupe WHERE meal_slot_id = ?",
            Long.class,
            mealSlotId);
    assertThat(dedupeRows).isEqualTo(1L);
  }

  @Test
  void cookEvent_autoComputedDedupeKey_replayWithSameMaterialNoOps() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "200.000", LocalDate.parse("2026-06-01"));
    UUID recipeId = UUID.randomUUID();
    UUID mealSlotId = UUID.randomUUID();

    // No dedupeKey supplied → the service computes a SHA-256 key from (mealSlot, recipe,
    // servings, batch). A second call with identical material must hit the same fence.
    CookEventCommand cmd =
        new CookEventCommand(
            recipeId,
            null,
            mealSlotId,
            2,
            false,
            null,
            false,
            null,
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("40.000"), "g")),
            null);

    postCookEvent(user.cookie(), cmd, 200);
    assertThat(quantityOf(itemId)).isEqualTo(160.0);

    MvcResult replay = postCookEvent(user.cookie(), cmd, 200);
    assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).get("updatedItems"))
        .isEmpty();
    assertThat(quantityOf(itemId)).isEqualTo(160.0);

    Long dedupeRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_cook_event_dedupe WHERE meal_slot_id = ?",
            Long.class,
            mealSlotId);
    assertThat(dedupeRows).isEqualTo(1L);
  }

  @Test
  void cookEvent_nonStrict_partialUnderflow_returns200_withUnderflowInBody_andPartialDeduction()
      throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "30.000", LocalDate.parse("2026-06-01"));

    // Request 100g but only 30g on hand; non-strict → deduct what's there, flag the rest.
    CookEventCommand cmd =
        ProvisionsTestData.cookEventCommand(
            UUID.randomUUID(), UUID.randomUUID(), "cheese:cheddar", new BigDecimal("100.000"), "g");

    MvcResult result = postCookEvent(user.cookie(), cmd, 200);
    assertThat(quantityOf(itemId)).isEqualTo(0.0);

    String body = result.getResponse().getContentAsString();
    assertThat(objectMapper.readTree(body).get("underflows")).hasSize(1);
    assertThat(
            objectMapper
                .readTree(body)
                .get("underflows")
                .get(0)
                .get("ingredientMappingKey")
                .asText())
        .isEqualTo("cheese:cheddar");
  }

  @Test
  void cookEvent_strictUnderflow_returns422_withInventoryUnderflowProblem() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "30.000", LocalDate.parse("2026-06-01"));

    CookEventCommand cmd =
        new CookEventCommand(
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            1,
            false,
            null,
            true, // strict
            null,
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("100.000"), "g")),
            null);

    mvc.perform(
            post("/api/v1/provisions/cook-event")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/inventory-underflow"))
        .andExpect(jsonPath("$.underflows").isArray())
        .andExpect(jsonPath("$.underflows[0].ingredientMappingKey").value("cheese:cheddar"));

    // @Transactional(noRollbackFor = InventoryUnderflowException.class): the partial deduction
    // committed even though the request 422'd.
    assertThat(quantityOf(itemId)).isEqualTo(0.0);
  }

  @Test
  void cookEvent_unitMismatch_emitsUnderflow_withNoDeduction() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId = seedItem(user.userId(), "milk:whole", "1000.000", LocalDate.parse("2026-06-01"));

    // Row is in 'g'; request is in 'ml' — no converter in 01g, so the row is skipped and the
    // whole request becomes an underflow with zero deduction.
    CookEventCommand cmd =
        ProvisionsTestData.cookEventCommand(
            UUID.randomUUID(), UUID.randomUUID(), "milk:whole", new BigDecimal("200.000"), "ml");

    MvcResult result = postCookEvent(user.cookie(), cmd, 200);
    assertThat(quantityOf(itemId)).isEqualTo(1000.0);
    String body = result.getResponse().getContentAsString();
    assertThat(objectMapper.readTree(body).get("updatedItems")).isEmpty();
    assertThat(objectMapper.readTree(body).get("underflows")).hasSize(1);
  }

  @Test
  void cookEvent_batchCook_returns422_batchCookNotSupported() throws Exception {
    AuthedUser user = registerUser();
    seedItem(user.userId(), "cheese:cheddar", "200.000", LocalDate.parse("2026-06-01"));

    CookEventCommand cmd =
        new CookEventCommand(
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            1,
            true, // isBatchCook
            null,
            false,
            null,
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("10.000"), "g")),
            null);

    mvc.perform(
            post("/api/v1/provisions/cook-event")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/batch-cook-not-supported"));
  }

  @Test
  void cookEvent_proportionOfRecipe_scalesDeduction() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "200.000", LocalDate.parse("2026-06-01"));

    // 100g * 0.5 proportion → 50g deducted.
    CookEventCommand cmd =
        new CookEventCommand(
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            1,
            false,
            new BigDecimal("0.5"),
            false,
            null,
            List.of(new RecipeIngredientUsage("cheese:cheddar", new BigDecimal("100.000"), "g")),
            null);

    postCookEvent(user.cookie(), cmd, 200);
    assertThat(quantityOf(itemId)).isEqualTo(150.0);
  }

  // ---------------- POST /meal-consumption ----------------

  @Test
  void mealConsumption_decrementsSpecificRow_writesCookEventAudit_publishesEvent()
      throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "250.000", LocalDate.parse("2026-06-01"));

    MealConsumptionCommand cmd =
        ProvisionsTestData.mealConsumptionCommand(itemId, new BigDecimal("100.000"));

    MvcResult result =
        mvc.perform(
                post("/api/v1/provisions/meal-consumption")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cmd)))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(quantityOf(itemId)).isEqualTo(150.0);
    String body = result.getResponse().getContentAsString();
    assertThat(objectMapper.readTree(body).get("updatedItems")).hasSize(1);
    assertThat(objectMapper.readTree(body).get("exhaustedItems")).isEmpty();

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE inventory_item_id = ? AND field_changed = 'quantity'"
                + " AND actor = 'COOK_EVENT'",
            Long.class,
            itemId);
    assertThat(auditCount).isEqualTo(1L);

    assertThat(eventCapture.adjusted()).hasSize(1);
    assertThat(eventCapture.adjusted().get(0).affectedItemIds()).containsExactly(itemId);
  }

  @Test
  void mealConsumption_floorsAtZero_andMarksExhausted() throws Exception {
    AuthedUser user = registerUser();
    UUID itemId =
        seedItem(user.userId(), "cheese:cheddar", "80.000", LocalDate.parse("2026-06-01"));

    MealConsumptionCommand cmd =
        ProvisionsTestData.mealConsumptionCommand(itemId, new BigDecimal("999.000"));

    MvcResult result =
        mvc.perform(
                post("/api/v1/provisions/meal-consumption")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cmd)))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(quantityOf(itemId)).isEqualTo(0.0);
    assertThat(itemStatusOf(itemId)).isEqualTo("EXHAUSTED");
    String body = result.getResponse().getContentAsString();
    assertThat(objectMapper.readTree(body).get("exhaustedItems").get(0).asText())
        .isEqualTo(itemId.toString());
  }

  @Test
  void mealConsumption_returns404_whenRowOwnedByOtherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    UUID itemId =
        seedItem(owner.userId(), "cheese:cheddar", "100.000", LocalDate.parse("2026-06-01"));

    MealConsumptionCommand cmd =
        ProvisionsTestData.mealConsumptionCommand(itemId, new BigDecimal("10.000"));

    mvc.perform(
            post("/api/v1/provisions/meal-consumption")
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/inventory-item-not-found"));
    // Owner's row untouched.
    assertThat(quantityOf(itemId)).isEqualTo(100.0);
  }

  @Test
  void mealConsumption_returns401_whenAnonymous() throws Exception {
    MealConsumptionCommand cmd =
        ProvisionsTestData.mealConsumptionCommand(UUID.randomUUID(), new BigDecimal("1.000"));
    mvc.perform(
            post("/api/v1/provisions/meal-consumption")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void mealConsumption_returns400_whenPortionsNegative() throws Exception {
    AuthedUser user = registerUser();
    String json = "{\"inventoryItemId\":\"%s\",\"portions\":-1}".formatted(UUID.randomUUID());
    mvc.perform(
            post("/api/v1/provisions/meal-consumption")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  // ---------------- POST /standalone-consumption ----------------

  @Test
  void standaloneConsumption_previewMode_returnsOldestRow_withNoMutation() throws Exception {
    AuthedUser user = registerUser();
    UUID older = seedItem(user.userId(), "veg:onion", "5.000", LocalDate.parse("2026-06-01"));
    seedItem(user.userId(), "veg:onion", "9.000", LocalDate.parse("2026-09-01"));

    StandaloneConsumptionCommand cmd =
        ProvisionsTestData.standaloneConsumptionCommand(
            "veg:onion", new BigDecimal("2.000"), "pcs", false);

    MvcResult result =
        mvc.perform(
                post("/api/v1/provisions/standalone-consumption")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cmd)))
            .andExpect(status().isOk())
            .andReturn();

    // Preview returns the oldest-expiry row but mutates nothing.
    String body = result.getResponse().getContentAsString();
    assertThat(objectMapper.readTree(body).get("id").asText()).isEqualTo(older.toString());
    assertThat(quantityOf(older)).isEqualTo(5.0);
  }

  @Test
  void standaloneConsumption_confirmed_decrementsOldestRow_andWritesAudit() throws Exception {
    AuthedUser user = registerUser();
    UUID older = seedItem(user.userId(), "veg:onion", "5.000", LocalDate.parse("2026-06-01"));
    UUID newer = seedItem(user.userId(), "veg:onion", "9.000", LocalDate.parse("2026-09-01"));

    StandaloneConsumptionCommand cmd =
        ProvisionsTestData.standaloneConsumptionCommand(
            "veg:onion", new BigDecimal("2.000"), "pcs", true);

    mvc.perform(
            post("/api/v1/provisions/standalone-consumption")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(older.toString()));

    assertThat(quantityOf(older)).isEqualTo(3.0);
    assertThat(quantityOf(newer)).isEqualTo(9.0);

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit"
                + " WHERE inventory_item_id = ? AND actor = 'NUTRITION_LOGGER'",
            Long.class,
            older);
    assertThat(auditCount).isEqualTo(1L);
  }

  @Test
  void standaloneConsumption_noMatchingRow_returns200_withEmptyBody() throws Exception {
    AuthedUser user = registerUser();

    StandaloneConsumptionCommand cmd =
        ProvisionsTestData.standaloneConsumptionCommand(
            "veg:nonexistent", new BigDecimal("1.000"), "pcs", true);

    MvcResult result =
        mvc.perform(
                post("/api/v1/provisions/standalone-consumption")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cmd)))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).isBlank();
  }

  @Test
  void standaloneConsumption_returns401_whenAnonymous() throws Exception {
    StandaloneConsumptionCommand cmd =
        ProvisionsTestData.standaloneConsumptionCommand(
            "veg:onion", new BigDecimal("1.000"), "pcs", true);
    mvc.perform(
            post("/api/v1/provisions/standalone-consumption")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cmd)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void standaloneConsumption_returns400_whenMappingKeyBlank() throws Exception {
    AuthedUser user = registerUser();
    String json =
        "{\"ingredientMappingKey\":\"\",\"quantity\":1,\"unit\":\"g\","
            + "\"userConfirmedDeduction\":true}";
    mvc.perform(
            post("/api/v1/provisions/standalone-consumption")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest());
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class CookEventCaptureConfig {
    @Bean
    CookEventCapture cookEventCapture() {
      return new CookEventCapture();
    }
  }

  static class CookEventCapture {
    private final List<ItemQuantityAdjustedEvent> adjusted = new CopyOnWriteArrayList<>();
    private final List<ItemRanOutEvent> ranOut = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onAdjusted(ItemQuantityAdjustedEvent event) {
      adjusted.add(event);
    }

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRanOut(ItemRanOutEvent event) {
      ranOut.add(event);
    }

    public List<ItemQuantityAdjustedEvent> adjusted() {
      return adjusted;
    }

    public List<ItemRanOutEvent> ranOut() {
      return ranOut;
    }

    public void clear() {
      adjusted.clear();
      ranOut.clear();
    }
  }
}
