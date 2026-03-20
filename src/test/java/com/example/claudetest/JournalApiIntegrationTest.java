package com.example.claudetest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class JournalApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JournalEntryRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void getEntries_returnsEmptyListInitially() throws Exception {
        mockMvc.perform(get("/api/entries"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void createEntry_returnsCreatedEntry() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("content", "Integration test entry"));

        mockMvc.perform(post("/api/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.content").value("Integration test entry"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void createAndRetrieveEntry() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("content", "First entry"));

        mockMvc.perform(post("/api/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        mockMvc.perform(get("/api/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("First entry"));
    }

    @Test
    void entriesReturnedInDescendingOrder() throws Exception {
        // Create two entries
        mockMvc.perform(post("/api/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "Older entry"))));

        // Small delay to ensure different timestamps
        Thread.sleep(10);

        mockMvc.perform(post("/api/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("content", "Newer entry"))));

        mockMvc.perform(get("/api/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Newer entry"))
                .andExpect(jsonPath("$[1].content").value("Older entry"));
    }

    @Test
    void deleteEntry_removesEntry() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("content", "To be deleted"));

        MvcResult result = mockMvc.perform(post("/api/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        Long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/entries/" + id))
                .andExpect(status().isNoContent());

        assertThat(repository.count()).isEqualTo(0);
    }

    @Test
    void createMultipleEntries_allPersisted() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/entries")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("content", "Entry " + i))));
        }

        mockMvc.perform(get("/api/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }
}
