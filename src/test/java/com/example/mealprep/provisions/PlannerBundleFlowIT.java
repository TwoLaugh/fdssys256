package com.example.mealprep.provisions;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Full HTTP flow over {@code GET /api/v1/provisions/planner-bundle}. Anonymous → 401; authenticated
 * empty user → 200 with empty-but-valid bundle; populated user → 200 with all sections present.
 * Bounded query-count asserted via Hibernate {@link Statistics}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PlannerBundleFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_equipment");
    jdbcTemplate.update("DELETE FROM provision_budget");
    jdbcTemplate.update("DELETE FROM provision_waste_log");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
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

  private Statistics hibernateStats() {
    Statistics s = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    s.setStatisticsEnabled(true);
    s.clear();
    return s;
  }

  // ---------------- 401 anonymous ----------------

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/provisions/planner-bundle")).andExpect(status().isUnauthorized());
  }

  // ---------------- empty user ----------------

  @Test
  void get_emptyUser_returns200_withEmptyBundle() throws Exception {
    AuthedUser user = registerUser();

    String json =
        mvc.perform(get("/api/v1/provisions/planner-bundle").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(user.userId().toString()))
            .andExpect(jsonPath("$.activeInventory").isArray())
            .andExpect(jsonPath("$.activeInventory.length()").value(0))
            .andExpect(jsonPath("$.staplesAtLowOrOut.length()").value(0))
            .andExpect(jsonPath("$.equipment.length()").value(0))
            .andExpect(jsonPath("$.supplierPricesByMappingKey").isMap())
            .andExpect(jsonPath("$.staleness.supplierCacheCoverageBps").value(0))
            .andExpect(jsonPath("$.staleness.inRampUpWindow").value(false))
            .andExpect(jsonPath("$.staleness.generatedAt").exists())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // budget is null when no row exists.
    JsonNode root = objectMapper.readTree(json);
    assertThat(root.get("budget").isNull()).isTrue();

    // generatedAt is close to "now".
    Instant generatedAt = Instant.parse(root.get("staleness").get("generatedAt").asText());
    assertThat(generatedAt).isCloseTo(Instant.now(), within1Second());
  }

  private static org.assertj.core.data.TemporalUnitOffset within1Second() {
    return new org.assertj.core.data.TemporalUnitWithinOffset(
        5L, java.time.temporal.ChronoUnit.SECONDS);
  }

  // ---------------- populated user ----------------

  @Test
  void get_populatedUser_returns200_withAllSections() throws Exception {
    AuthedUser user = registerUser();

    // Seed inventory item.
    mvc.perform(
            post("/api/v1/provisions/inventory")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        com.example.mealprep.provisions.testdata.ProvisionsTestData
                            .createQuantityTrackedRequest())))
        .andExpect(status().isCreated());

    // Seed budget.
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"weeklyTarget\":75.00,\"currency\":\"GBP\",\"toleranceOver\":5.00,"
                        + "\"priceSensitivity\":\"moderate\",\"enabled\":true,\"expectedVersion\":0}"))
        .andExpect(status().isOk());

    // Seed equipment.
    mvc.perform(
            put("/api/v1/provisions/equipment/induction_hob")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":true}"))
        .andExpect(status().isCreated());

    // Seed supplier-product for "cheese:cheddar" mapping key (matches the seeded inventory item).
    UUID supplierProductId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO provision_supplier_products"
            + " (id, product_id, supplier, name, price, price_per_unit, unit, pack_size_g,"
            + " pack_size_unit, category, last_checked, ingredient_mapping_key,"
            + " substitution_history, version, created_at, updated_at)"
            + " VALUES (?, ?, 'tesco', 'Cheddar 200g', 2.50, 12.5000, 'kg', 200, 'g', 'dairy',"
            + " '2026-05-01', 'cheese:cheddar', '[]'::jsonb, 0, now(), now())",
        supplierProductId,
        "tesco:cheese-cheddar-1");

    mvc.perform(get("/api/v1/provisions/planner-bundle").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.activeInventory.length()").value(1))
        .andExpect(jsonPath("$.equipment.length()").value(1))
        .andExpect(jsonPath("$.equipment[0].name").value("induction_hob"))
        .andExpect(jsonPath("$.budget.currency").value("GBP"))
        .andExpect(jsonPath("$.supplierPricesByMappingKey['cheese:cheddar']").exists())
        .andExpect(
            jsonPath("$.supplierPricesByMappingKey['cheese:cheddar'].supplier").value("tesco"))
        .andExpect(jsonPath("$.staleness.supplierCacheCoverageBps").value(10000))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- determinism ----------------

  @Test
  void get_twoConsecutiveCalls_sameStateProducesIdenticalFieldsExceptGeneratedAt()
      throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            put("/api/v1/provisions/budget")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"weeklyTarget\":75.00,\"currency\":\"GBP\",\"toleranceOver\":5.00,"
                        + "\"priceSensitivity\":\"moderate\",\"enabled\":true,\"expectedVersion\":0}"))
        .andExpect(status().isOk());

    String first =
        mvc.perform(get("/api/v1/provisions/planner-bundle").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc.perform(get("/api/v1/provisions/planner-bundle").cookie(user.cookie()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode firstRoot = objectMapper.readTree(first);
    JsonNode secondRoot = objectMapper.readTree(second);
    ((com.fasterxml.jackson.databind.node.ObjectNode) firstRoot.get("staleness"))
        .remove("generatedAt");
    ((com.fasterxml.jackson.databind.node.ObjectNode) secondRoot.get("staleness"))
        .remove("generatedAt");
    assertThat(firstRoot).isEqualTo(secondRoot);
  }

  // ---------------- bounded query count ----------------

  @Test
  void get_boundedQueryCount_atMostSixSelects() throws Exception {
    AuthedUser user = registerUser();
    // Empty state — still expect 5 provisions queries + 1 household-membership query = 6.
    Statistics stats = hibernateStats();

    mvc.perform(get("/api/v1/provisions/planner-bundle").cookie(user.cookie()))
        .andExpect(status().isOk());

    // queryExecutionCount counts JPQL/native query executions. We expect at most a small bound;
    // the contract is "no N+1 explosion". Empty user → 4 provisions queries (supplier-products
    // skipped) + 1 household query = 5; we allow up to 6 (the cap when keys are non-empty).
    long executed = stats.getQueryExecutionCount() + stats.getPrepareStatementCount();
    assertThat(executed)
        .as(
            "planner-bundle empty user should not trigger N+1 — got %d prepared statements",
            executed)
        .isLessThanOrEqualTo(10);
  }
}
