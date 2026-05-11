package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.example.mealprep.provisions.event.SubstitutionAcceptedEvent;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Full HTTP flow over the supplier-products aggregate. Exercises POST insert (201 + Location), POST
 * update (200 with preserved history), GET paginated search, the substitution append + event,
 * 404/409/validation paths, and the JSONB round-trip across multiple inserts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  SupplierProductsFlowIT.SupplierEventCaptureConfig.class
})
@ActiveProfiles("test")
class SupplierProductsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private SupplierEventCapture eventCapture;
  @Autowired private ProvisionsModule provisionsModule;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

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

  private static String upsertBody(
      String productId,
      String supplier,
      String name,
      String price,
      String lastChecked,
      String mappingKey) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"productId\":\"").append(productId).append("\"");
    sb.append(",\"supplier\":\"").append(supplier).append("\"");
    sb.append(",\"name\":\"").append(name).append("\"");
    if (price != null) sb.append(",\"price\":").append(price);
    sb.append(",\"lastChecked\":\"").append(lastChecked).append("\"");
    if (mappingKey != null)
      sb.append(",\"ingredientMappingKey\":\"").append(mappingKey).append("\"");
    sb.append("}");
    return sb.toString();
  }

  // ---------------- POST upsert ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_insertPath_returns201_withLocationAndEmptyHistory() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.productId").value("tesco:1"))
        .andExpect(jsonPath("$.supplier").value("tesco"))
        .andExpect(jsonPath("$.substitutionHistory").isArray())
        .andExpect(jsonPath("$.substitutionHistory.length()").value(0))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void post_updatePath_returns200_andPreservesSubstitutionHistory() throws Exception {
    AuthedUser user = registerUser();
    // Insert first.
    String createResponse =
        mvc.perform(
                post("/api/v1/provisions/supplier-products")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(createResponse).get("id").asText();

    // Record one substitution so history is non-empty.
    mvc.perform(
            post("/api/v1/provisions/supplier-products/" + id + "/substitutions")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"record\":{\"date\":\"2026-05-09\",\"substitutedWithProductId\":\"tesco:99\",\"accepted\":true},"
                        + "\"userAccepted\":true,\"expectedVersion\":0}"))
        .andExpect(status().isOk());

    // Re-POST with updated fields — history preserved.
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    upsertBody("tesco:1", "tesco", "Onion updated", "0.35", "2026-05-10", "onion")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Onion updated"))
        .andExpect(jsonPath("$.price").value(0.35))
        .andExpect(jsonPath("$.substitutionHistory.length()").value(1))
        .andExpect(jsonPath("$.substitutionHistory[0].substitutedWithProductId").value("tesco:99"));
  }

  @Test
  void post_returns400_whenProductIdBlank() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenLastCheckedInFuture() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("tesco:1", "tesco", "Onion", "0.30", "2099-01-01", "onion")))
        .andExpect(status().isBadRequest());
  }

  // ---------------- GET search ----------------

  @Test
  void get_search_returnsAllRows_whenNoFilters() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("ocado:9", "ocado", "Garlic", "0.50", "2026-05-02", "garlic")))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/provisions/supplier-products").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void get_search_filtersByMappingKeyAndSupplier() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/provisions/supplier-products")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(upsertBody("ocado:9", "ocado", "Garlic", "0.50", "2026-05-02", "garlic")))
        .andExpect(status().isCreated());

    mvc.perform(
            get("/api/v1/provisions/supplier-products?mappingKey=onion&supplier=tesco")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].productId").value("tesco:1"));
  }

  @Test
  void get_search_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/supplier-products")).andExpect(status().isUnauthorized());
  }

  // ---------------- POST substitution ----------------

  @Test
  void postSubstitution_returns404_whenIdMissing() throws Exception {
    AuthedUser user = registerUser();
    UUID id = UUID.randomUUID();
    mvc.perform(
            post("/api/v1/provisions/supplier-products/" + id + "/substitutions")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"record\":{\"date\":\"2026-05-09\",\"substitutedWithProductId\":\"tesco:99\",\"accepted\":true},"
                        + "\"userAccepted\":true,\"expectedVersion\":0}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/supplier-product-not-found"));
  }

  @Test
  void postSubstitution_returns409_onStaleVersion() throws Exception {
    AuthedUser user = registerUser();
    String createResponse =
        mvc.perform(
                post("/api/v1/provisions/supplier-products")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(createResponse).get("id").asText();

    mvc.perform(
            post("/api/v1/provisions/supplier-products/" + id + "/substitutions")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"record\":{\"date\":\"2026-05-09\",\"substitutedWithProductId\":\"tesco:99\",\"accepted\":true},"
                        + "\"userAccepted\":true,\"expectedVersion\":99}"))
        .andExpect(status().isConflict());
  }

  @Test
  void postSubstitution_happyPath_publishesEvent_andAppendsHistory() throws Exception {
    AuthedUser user = registerUser();
    String createResponse =
        mvc.perform(
                post("/api/v1/provisions/supplier-products")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(createResponse).get("id").asText();

    mvc.perform(
            post("/api/v1/provisions/supplier-products/" + id + "/substitutions")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"record\":{\"date\":\"2026-05-09\",\"substitutedWithProductId\":\"tesco:99\",\"accepted\":true,\"notes\":\"red onion swap\"},"
                        + "\"userAccepted\":true,\"expectedVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.substitutionHistory.length()").value(1))
        .andExpect(jsonPath("$.substitutionHistory[0].substitutedWithProductId").value("tesco:99"))
        .andExpect(jsonPath("$.substitutionHistory[0].notes").value("red onion swap"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).supplierProductId()).isEqualTo(UUID.fromString(id));
    assertThat(eventCapture.events().get(0).userId()).isEqualTo(user.userId());
    assertThat(eventCapture.events().get(0).substitutedProductId()).isEqualTo("tesco:99");
  }

  // ---------------- JSONB round-trip ----------------

  @Test
  void jsonb_roundTrip_threeSubstitutions_preservesOrderAndFields() throws Exception {
    AuthedUser user = registerUser();
    String createResponse =
        mvc.perform(
                post("/api/v1/provisions/supplier-products")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        upsertBody("tesco:1", "tesco", "Onion", "0.30", "2026-05-01", "onion")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(createResponse).get("id").asText();

    long expectedVersion = 0L;
    for (int i = 0; i < 3; i++) {
      String body =
          "{\"record\":{\"date\":\"2026-05-0"
              + (i + 1)
              + "\",\"substitutedWithProductId\":\"tesco:"
              + i
              + "\",\"accepted\":"
              + (i % 2 == 0)
              + (i == 1 ? ",\"notes\":null" : "")
              + "},\"userAccepted\":"
              + (i % 2 == 0)
              + ",\"expectedVersion\":"
              + expectedVersion
              + "}";
      mvc.perform(
              post("/api/v1/provisions/supplier-products/" + id + "/substitutions")
                  .cookie(user.cookie())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk());
      expectedVersion++;
    }

    mvc.perform(get("/api/v1/provisions/supplier-products?mappingKey=onion").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].substitutionHistory.length()").value(3))
        .andExpect(
            jsonPath("$.content[0].substitutionHistory[0].substitutedWithProductId")
                .value("tesco:0"))
        .andExpect(
            jsonPath("$.content[0].substitutionHistory[1].substitutedWithProductId")
                .value("tesco:1"))
        .andExpect(
            jsonPath("$.content[0].substitutionHistory[2].substitutedWithProductId")
                .value("tesco:2"));
  }

  // ---------------- Cross-module batch helpers ----------------

  @Test
  void crossModule_getSupplierProductByMappingKey_returnsCheapest() throws Exception {
    AuthedUser user = registerUser();
    // Two onion rows with different price_per_unit.
    insertDirect(
        "tesco",
        "tesco:expensive",
        "onion",
        new java.math.BigDecimal("2.0000"),
        LocalDate.parse("2026-05-05"));
    insertDirect(
        "tesco",
        "tesco:cheap",
        "onion",
        new java.math.BigDecimal("1.0000"),
        LocalDate.parse("2026-05-01"));

    assertThat(provisionsModule.query().getSupplierProductByMappingKey("onion"))
        .isPresent()
        .hasValueSatisfying(dto -> assertThat(dto.productId()).isEqualTo("tesco:cheap"));
  }

  @Test
  void crossModule_getSupplierProductsByMappingKeys_returnsBatchedCheapest() throws Exception {
    AuthedUser user = registerUser();
    insertDirect(
        "tesco",
        "tesco:onion1",
        "onion",
        new java.math.BigDecimal("2.0000"),
        LocalDate.parse("2026-05-05"));
    insertDirect(
        "tesco",
        "tesco:onion2",
        "onion",
        new java.math.BigDecimal("1.0000"),
        LocalDate.parse("2026-05-01"));
    insertDirect(
        "ocado",
        "ocado:garlic",
        "garlic",
        new java.math.BigDecimal("5.0000"),
        LocalDate.parse("2026-05-01"));

    Map<String, com.example.mealprep.provisions.api.dto.SupplierProductDto> result =
        provisionsModule
            .query()
            .getSupplierProductsByMappingKeys(List.of("onion", "garlic", "missing"));

    assertThat(result).hasSize(2);
    assertThat(result.get("onion").productId()).isEqualTo("tesco:onion2");
    assertThat(result.get("garlic").productId()).isEqualTo("ocado:garlic");
    assertThat(result).doesNotContainKey("missing");
  }

  private void insertDirect(
      String supplier,
      String productId,
      String mappingKey,
      java.math.BigDecimal pricePerUnit,
      LocalDate lastChecked) {
    jdbcTemplate.update(
        "INSERT INTO provision_supplier_products"
            + " (id, product_id, supplier, name, price_per_unit, last_checked,"
            + "  substitution_history, ingredient_mapping_key, version, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb, ?, 0, now(), now())",
        UUID.randomUUID(),
        productId,
        supplier,
        "row-" + productId,
        pricePerUnit,
        lastChecked,
        mappingKey);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class SupplierEventCaptureConfig {
    @Bean
    SupplierEventCapture supplierEventCapture() {
      return new SupplierEventCapture();
    }
  }

  static class SupplierEventCapture {
    private final List<SubstitutionAcceptedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onChanged(SubstitutionAcceptedEvent event) {
      events.add(event);
    }

    public List<SubstitutionAcceptedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
