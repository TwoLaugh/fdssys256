package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.event.InventoryItemUpsertedEvent;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
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
 * Full HTTP flow over the inventory aggregate. Registers a user, exercises the four endpoints
 * (list, get-by-id, create, update) and validates the relevant ProblemDetail/auth/version
 * behaviour. JSON contract is checked against the OpenAPI spec via swagger-request-validator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  InventoryFlowIT.InventoryEventCaptureConfig.class
})
@ActiveProfiles("test")
class InventoryFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private ProvisionUpdateService provisionUpdateService;
  @Autowired private ProvisionQueryService provisionQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private InventoryEventCapture eventCapture;

  @AfterEach
  void cleanup() {
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

  // ---------------- POST /api/v1/provisions/inventory ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/provisions/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        ProvisionsTestData.createQuantityTrackedRequest())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_returns201_andLocationHeader_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser();
    CreateInventoryItemRequest body = ProvisionsTestData.createQuantityTrackedRequest();

    MvcResult result =
        mvc.perform(
                post("/api/v1/provisions/inventory")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.userId").value(user.userId().toString()))
            .andExpect(jsonPath("$.name").value("Cheddar"))
            .andExpect(jsonPath("$.itemStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    UUID itemId = UUID.fromString(objectMapper.readTree(responseBody).get("id").asText());
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("/api/v1/provisions/inventory/" + itemId);

    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE inventory_item_id = ?",
            Long.class,
            itemId);
    assertThat(auditCount).isEqualTo(1L);
    String fieldChanged =
        jdbcTemplate.queryForObject(
            "SELECT field_changed FROM provision_inventory_audit WHERE inventory_item_id = ?",
            String.class,
            itemId);
    assertThat(fieldChanged).isEqualTo("created");
    String actor =
        jdbcTemplate.queryForObject(
            "SELECT actor FROM provision_inventory_audit WHERE inventory_item_id = ?",
            String.class,
            itemId);
    assertThat(actor).isEqualTo("USER");

    assertThat(eventCapture.events()).hasSize(1);
    InventoryItemUpsertedEvent captured = eventCapture.events().get(0);
    assertThat(captured.itemId()).isEqualTo(itemId);
    assertThat(captured.userId()).isEqualTo(user.userId());
    assertThat(captured.actor()).isEqualTo(AuditActor.USER);
  }

  @Test
  void post_returns400_whenSpiceRackWithQuantityTracking() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "name": "Salt",
          "category": "seasoning",
          "storageLocation": "SPICE_RACK",
          "trackingMode": "QUANTITY",
          "quantity": 100,
          "unit": "g",
          "isStaple": true,
          "source": "MANUAL_ADD"
        }
        """;

    mvc.perform(
            post("/api/v1/provisions/inventory")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void post_returns400_whenFreezerWithoutFreezerExtension() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "name": "Frozen Peas",
          "category": "vegetable",
          "storageLocation": "FREEZER",
          "trackingMode": "QUANTITY",
          "quantity": 500,
          "unit": "g",
          "isStaple": false,
          "source": "TESCO_ORDER"
        }
        """;

    mvc.perform(
            post("/api/v1/provisions/inventory")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void post_returns400_whenQuantityNegative() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "name": "Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": -1,
          "unit": "g",
          "isStaple": false,
          "source": "MANUAL_ADD"
        }
        """;

    mvc.perform(
            post("/api/v1/provisions/inventory")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void post_returns400_whenQuantityTrackedWithoutUnit() throws Exception {
    AuthedUser user = registerUser();
    String json =
        """
        {
          "name": "Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": 250,
          "isStaple": false,
          "source": "MANUAL_ADD"
        }
        """;

    mvc.perform(
            post("/api/v1/provisions/inventory")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // ---------------- GET /api/v1/provisions/inventory/{itemId} ----------------

  @Test
  void getById_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/inventory/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getById_returns200_whenOwned() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(get("/api/v1/provisions/inventory/" + created.id()).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(created.id().toString()))
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getById_returns404_whenOwnedByOtherUser_doesNotLeakExistence() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            owner.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    mvc.perform(get("/api/v1/provisions/inventory/" + created.id()).cookie(intruder.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/inventory-item-not-found"));
  }

  @Test
  void getById_returns404_whenMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/provisions/inventory/" + UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // ---------------- GET /api/v1/provisions/inventory ----------------

  @Test
  void list_returnsActiveItemsOnly_paginated() throws Exception {
    AuthedUser user = registerUser();
    provisionUpdateService.createInventoryItem(
        user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);
    provisionUpdateService.createInventoryItem(
        user.userId(), ProvisionsTestData.createStatusTrackedRequest(), AuditActor.USER);

    mvc.perform(get("/api/v1/provisions/inventory").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.number").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void list_clampsSizeAbove100ToMax() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/provisions/inventory?size=500").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  @Test
  void list_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/inventory")).andExpect(status().isUnauthorized());
  }

  // ---------------- PUT /api/v1/provisions/inventory/{itemId} ----------------

  @Test
  void put_returns200_andBumpsVersion_whenChanged() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    String json =
        """
        {
          "name": "Mature Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": 250.000,
          "unit": "g",
          "costPaid": 3.49,
          "isStaple": false,
          "expiryDate": "2026-06-01",
          "ingredientMappingKey": "cheese:cheddar",
          "source": "MANUAL_ADD",
          "itemStatus": "ACTIVE",
          "expectedVersion": 0
        }
        """;

    mvc.perform(
            put("/api/v1/provisions/inventory/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Mature Cheddar"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void put_returns409_whenStaleExpectedVersion() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    String json =
        """
        {
          "name": "Mature Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": 300.000,
          "unit": "g",
          "isStaple": false,
          "source": "MANUAL_ADD",
          "itemStatus": "ACTIVE",
          "expectedVersion": 99
        }
        """;

    mvc.perform(
            put("/api/v1/provisions/inventory/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/concurrent-update"));
  }

  @Test
  void put_returns404_whenOwnedByOtherUser() throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser intruder = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            owner.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    String json =
        """
        {
          "name": "Mature Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": 250.000,
          "unit": "g",
          "isStaple": false,
          "source": "MANUAL_ADD",
          "itemStatus": "ACTIVE",
          "expectedVersion": 0
        }
        """;

    mvc.perform(
            put("/api/v1/provisions/inventory/" + created.id())
                .cookie(intruder.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void put_writesOneAuditRowPerChangedField_andDoesNotWriteForNoOp() throws Exception {
    AuthedUser user = registerUser();
    InventoryItemDto created =
        provisionUpdateService.createInventoryItem(
            user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    // No-op: same values
    String sameValues =
        """
        {
          "name": "Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": 250.000,
          "unit": "g",
          "costPaid": 3.49,
          "isStaple": false,
          "expiryDate": "2026-06-01",
          "ingredientMappingKey": "cheese:cheddar",
          "source": "MANUAL_ADD",
          "itemStatus": "ACTIVE",
          "expectedVersion": 0
        }
        """;
    mvc.perform(
            put("/api/v1/provisions/inventory/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(sameValues))
        .andExpect(status().isOk());

    Long auditCountAfterNoop =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE inventory_item_id = ?"
                + " AND field_changed <> 'created'",
            Long.class,
            created.id());
    assertThat(auditCountAfterNoop).isEqualTo(0L);

    // Change name + quantity
    String changed =
        """
        {
          "name": "Mature Cheddar",
          "category": "dairy",
          "storageLocation": "FRIDGE",
          "trackingMode": "QUANTITY",
          "quantity": 300.000,
          "unit": "g",
          "costPaid": 3.49,
          "isStaple": false,
          "expiryDate": "2026-06-01",
          "ingredientMappingKey": "cheese:cheddar",
          "source": "MANUAL_ADD",
          "itemStatus": "ACTIVE",
          "expectedVersion": 0
        }
        """;
    mvc.perform(
            put("/api/v1/provisions/inventory/" + created.id())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(changed))
        .andExpect(status().isOk());

    Long auditCountAfterChange =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM provision_inventory_audit WHERE inventory_item_id = ?"
                + " AND field_changed <> 'created'",
            Long.class,
            created.id());
    assertThat(auditCountAfterChange).isEqualTo(2L);
  }

  // ---------------- getActiveInventoryByMappingKey (cross-module read) ----------------

  @Test
  void getActiveInventoryByMappingKey_returnsActiveRowsOldestExpiryFirst_scopedToUser()
      throws Exception {
    AuthedUser owner = registerUser();
    AuthedUser other = registerUser();

    // Two ACTIVE soy_sauce rows for the owner, plus one for another user (cross-tenant guard).
    InventoryItemDto newer =
        provisionUpdateService.createInventoryItem(
            owner.userId(), soySauceRequest(LocalDate.parse("2026-07-01")), AuditActor.USER);
    InventoryItemDto older =
        provisionUpdateService.createInventoryItem(
            owner.userId(), soySauceRequest(LocalDate.parse("2026-06-01")), AuditActor.USER);
    provisionUpdateService.createInventoryItem(
        other.userId(), soySauceRequest(LocalDate.parse("2026-05-30")), AuditActor.USER);

    // An EXHAUSTED soy_sauce row for the owner must NOT surface (ACTIVE-only filter).
    InventoryItemDto spent =
        provisionUpdateService.createInventoryItem(
            owner.userId(), soySauceRequest(LocalDate.parse("2026-05-25")), AuditActor.USER);
    provisionUpdateService.markExhausted(spent.id(), owner.userId());

    List<InventoryItemDto> rows =
        provisionQueryService.getActiveInventoryByMappingKey(owner.userId(), "soy_sauce");

    // Owner's two ACTIVE rows only, oldest-expiry first; other user's + exhausted row excluded.
    assertThat(rows).extracting(InventoryItemDto::id).containsExactly(older.id(), newer.id());
    assertThat(rows).allMatch(r -> r.userId().equals(owner.userId()));
  }

  @Test
  void getActiveInventoryByMappingKey_noMatchingRows_returnsEmpty() throws Exception {
    AuthedUser user = registerUser();
    provisionUpdateService.createInventoryItem(
        user.userId(), ProvisionsTestData.createQuantityTrackedRequest(), AuditActor.USER);

    assertThat(provisionQueryService.getActiveInventoryByMappingKey(user.userId(), "soy_sauce"))
        .isEmpty();
  }

  private static CreateInventoryItemRequest soySauceRequest(LocalDate expiry) {
    return new CreateInventoryItemRequest(
        "Soy Sauce",
        "condiment",
        StorageLocation.CUPBOARD,
        TrackingMode.QUANTITY,
        new BigDecimal("150.000"),
        "ml",
        new BigDecimal("1.50"),
        null,
        false,
        expiry,
        "soy_sauce",
        null,
        ItemSource.MANUAL_ADD,
        null,
        null);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class InventoryEventCaptureConfig {
    @Bean
    InventoryEventCapture inventoryEventCapture() {
      return new InventoryEventCapture();
    }
  }

  static class InventoryEventCapture {
    private final List<InventoryItemUpsertedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onUpserted(InventoryItemUpsertedEvent event) {
      events.add(event);
    }

    public List<InventoryItemUpsertedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
