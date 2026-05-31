package com.example.mealprep.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
 * Full-context auth IT for the AI admin observability surface (finding {@code ai-10}). Drives the
 * real deny-by-default {@code AuthSecurityConfig} chain plus the imperative {@code AiAdminGuard}.
 *
 * <p><b>The ai-10 fix made real:</b> with the default empty admin allowlist ({@code
 * mealprep.ai.admin.user-ids}), a genuinely <em>authenticated</em> (but non-admin) user is rejected
 * with 403 — the gap the original {@code @PreAuthorize}-only contract did not close (and that the
 * {@code AdminAiControllerIT}, which excludes {@code SecurityAutoConfiguration}, could not catch).
 * Anonymous requests get 401 from the filter chain before the controller is reached.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdminAiAuthIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM auth_sessions");
    jdbcTemplate.update("DELETE FROM auth_login_attempts");
    jdbcTemplate.update("DELETE FROM auth_users");
  }

  private Cookie registerAndLogin() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("ai-admin-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return result.getResponse().getCookie(authProperties.cookieName());
  }

  @Test
  void costSummary_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/admin/ai/cost-summary")).andExpect(status().isUnauthorized());
  }

  @Test
  void costSummary_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(get("/api/v1/admin/ai/cost-summary").cookie(cookie))
        .andExpect(status().isForbidden());
  }

  @Test
  void callLog_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(get("/api/v1/admin/ai/call-log").cookie(cookie)).andExpect(status().isForbidden());
  }

  @Test
  void promptTemplates_returns403_whenAuthenticatedButNotAdmin() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(get("/api/v1/admin/ai/prompt-templates").cookie(cookie))
        .andExpect(status().isForbidden());
  }
}
